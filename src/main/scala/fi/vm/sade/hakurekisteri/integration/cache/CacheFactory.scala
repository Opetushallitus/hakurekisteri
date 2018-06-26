package fi.vm.sade.hakurekisteri.integration.cache

import java.io._

import akka.actor.ActorSystem
import akka.util.ByteString
import fi.vm.sade.hakurekisteri.integration.ExecutorUtil
import fi.vm.sade.properties.OphProperties
import org.apache.commons.io.IOUtils
import redis.{ByteStringFormatter, RedisClient}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

trait CacheFactory {

  val defaultExpirationDuration: Long = 60.minutes.toMillis
  def prefix(clazz:Class[_], prefix:String) = s"${clazz.getName}:${prefix}"

  def getInstance[K, T <: Serializable](expirationDurationMillis:Long,
                        clazz:Class[_],
                        classOfT: Class[_],
                        cacheKeyPrefix:String)(implicit m: Manifest[T]): MonadCache[Future, K, T] = {
    getInstance[K, T](classOfT, expirationDurationMillis, prefix(clazz, cacheKeyPrefix))
  }

  def getInstance[K, T <: Serializable](classOfT: Class[_],
                                        expirationDurationMillis:Long,
                                        cacheKeyPrefix:String)(implicit m: Manifest[T]): MonadCache[Future, K, T]
}

object CacheFactory {
  def apply(config: OphProperties)(implicit system: ActorSystem): CacheFactory = config.getOrElse("suoritusrekisteri.cache.redis.enabled", "false") match {
    case p if "TRUE".equalsIgnoreCase(p) => new RedisCacheFactory(config)
    case _ => new InMemoryCacheFactory
  }

  class InMemoryCacheFactory extends CacheFactory {
    override def getInstance[K, T](classOfT: Class[_], expirationDurationMillis: Long, cacheKeyPrefix: String)(implicit m: Manifest[T]): InMemoryFutureCache[K, T] = {
      new InMemoryFutureCache[K, T](expirationDurationMillis)
    }
  }

  class RedisCacheFactory(config: OphProperties)(implicit system: ActorSystem) extends CacheFactory {

    val r = {
      val host = config.getOrElse("suoritusrekisteri.cache.redis.host", "")
      if ("".equals(host)) throw new RuntimeException(s"No configuration for Redis host found")
      val port = config.getOrElse("suoritusrekisteri.cache.redis.port", "6379").toInt
      org.slf4j.LoggerFactory.getLogger(getClass).info(s"Using redis cache ${host}:${port}")
      RedisClient(host = host, port = port)
    }

    override def getInstance[K, T](classOfT: Class[_], expirationDurationMillis: Long, cacheKeyPrefix: String)(implicit m: Manifest[T]) = {
      new RedisCache[K, T](
        r,
        classOfT,
        expirationDurationMillis,
        cacheKeyPrefix,
        config.getProperty("suoritusrekisteri.cache.redis.numberOfWaitersToLog").toInt,
        config.getProperty("suoritusrekisteri.cache.redis.cacheHandlingThreadPoolSize").toInt,
        config.getProperty("suoritusrekisteri.cache.redis.slowRedisRequestThresholdMillis").toInt)
    }

    class RedisCache[K, T](val r: RedisClient,
                           classOfT: Class[_],
                           val expirationDurationMillis: Long,
                           val cacheKeyPrefix: String,
                           limitOfWaitingClientsToLog: Int,
                           cacheHandlingThreadPoolSize: Int,
                           slowRedisRequestThresholdMillis: Int
                          )(implicit m: Manifest[T])  extends MonadCache[Future, K, T] {

      val logger = org.slf4j.LoggerFactory.getLogger(getClass)

      implicit val executor: ExecutionContext = ExecutorUtil.createExecutor(cacheHandlingThreadPoolSize, s"cache-access-$cacheKeyPrefix")

      implicit val byteStringFormatter = new ByteStringFormatterImpl[T]
      implicit val byteStringFormatterLong = ByteStringFormatterLongImpl

      private val updateConcurrencyHandler = new RedisUpdateConcurrencyHandler[K,T](r, limitOfWaitingClientsToLog)

      private val versionPrefixKey = s"${cacheKeyPrefix}:version"
      private val newVersion: Long = {
        val streamClass = java.io.ObjectStreamClass.lookup(classOfT)
        try {
          streamClass.getSerialVersionUID
        } catch {
          case e: NullPointerException =>
            val msg = s"Class ${classOfT.getName} is not serializable - getSerialVersionUID resulted in NullPointerException"
            logger.error(msg)
            throw new RedisCacheInitializationException(msg)
        }
      }

      init()

      private def init(): Unit = {
        if (classOfT.getName.equals("scala.Option")) {
          logger.error(s"In cache with prefix $cacheKeyPrefix, given classOfT parameter was an Option. Please use the" +
            " inner type instead, e.g. classOf[A] instead of classOf[Option[A]]. Otherwise changes to the serializable" +
            " class will not be detected and deserialization may break.")
        }

        val runTimeTypeClass = manifest[T].runtimeClass
        if (!runTimeTypeClass.equals(classOfT) && !runTimeTypeClass.getName.equals("scala.Option")) {
          logger.warn(s"In cache with prefix $cacheKeyPrefix, class of type parameter T (${runTimeTypeClass.getName})" +
            s" was not the same as given classOfT (${classOfT.getName}).")
        }

        val f: Future[Unit] = getVersion.flatMap {
          case Some(oldVersion) if oldVersion == newVersion =>
            logger.info(s"Serial version UID has not changed in cache with prefix $cacheKeyPrefix, is still $newVersion.")
            Future.successful(())
          case Some(oldVersion)  =>
            logger.warn(s"Serial version UID has changed from $oldVersion to $newVersion in cache with prefix $cacheKeyPrefix. Deleting all keys.")
            clearCache(oldVersion)
          case None =>
            logger.info(s"No version key in cache with prefix $cacheKeyPrefix. It will be assumed that the version has not changed.")
            setVersion(newVersion)
        }
        f.onFailure{case t =>
          throw new RedisCacheInitializationException(s"Failed to initialize cache with prefix $cacheKeyPrefix", t)
        }
        Await.result(f, 2.minute)
      }

      def getVersion: Future[Option[Long]] = {
        r.get[Long](versionPrefixKey)
      }

      private def clearCache(oldVersion: Long): Future[Unit] = {
          val pattern = s"${cacheKeyPrefix}:*"
          val count = config.getOrElse("suoritusrekisteri.cache.redis.scancount", "200").toInt
          logger.info(s"Scanning Redis cache with COUNT $count")

          var index = 0
          var totalDeleted = 0L
          do {
            val (newIndex, deletedCount) = Await.result(deletingScan(index, count, pattern), 1.minute)
            index = newIndex
            totalDeleted += deletedCount
          } while (index != 0)

          logger.info(s"Finished scanning and deleting. Total number of keys successfully deleted: $totalDeleted")
          setVersion(newVersion)
      }

      private def deletingScan(startIndex: Int, scanCount: Int, pattern: String): Future[(Int,Long)] = {
        r.scan(cursor = startIndex, count = Some(scanCount), matchGlob = Some(pattern))
          .flatMap { result =>
            val keys = result.data
            val keysCount = keys.length
            val fInner: Future[Long] = if (keys.nonEmpty) {
              logger.trace(s"SCAN returned $keysCount matching keys for PATTERN ${pattern} between indices $startIndex and ${result.index}, deleting.")
              r.del(keys: _*)
            } else {
              logger.trace(s"SCAN returned no matching keys for PATTERN ${pattern} between indices $startIndex and ${result.index}.")
              Future.successful(0L)
            }
            fInner.map { deletedCount =>
              if (deletedCount > 0) {
                logger.trace(s"Successfully deleted $deletedCount keys")
              }
              if (keysCount != deletedCount) {
                logger.warn(s"Number of keys from SCAN was $keysCount but DEL command returned $deletedCount")
              }
              (result.index, deletedCount)
            }
          }
      }

      private def setVersion(version: Long): Future[Unit] = {
        logger.warn(s"Storing new version value $version to Redis cache with key $versionPrefixKey")
        r.set[Long](versionPrefixKey, version, Some(expirationDurationMillis))
          .flatMap { b: Boolean =>
            if (!b) {
              Future.failed(new RuntimeException(s"Failed to store version in Redis cache with prefix $cacheKeyPrefix"))
            } else {
              Future.successful(())
            }
          }
      }

      def +(key: K, value: T): Future[_] =  {
        val prefixKey = k(key)
        logger.debug(s"Adding value with key $prefixKey to Redis cache")
        r.set[T](prefixKey, value, pxMilliseconds = Some(expirationDurationMillis))
      }

      def -(key: K): Unit = r.del(k(key))

      def contains(key: K): Future[Boolean] = {
        val prefixKey = k(key)
        val startTime = System.currentTimeMillis
        r.exists(prefixKey).collect {
          case result =>
            val duration = System.currentTimeMillis - startTime
            if (duration > slowRedisRequestThresholdMillis) {
              logger.info(s"Checking contains $prefixKey from Redis took $duration ms")
            }
            result
        }
      }

      private def get(key: K): Future[Option[T]] = {
        val prefixKey = k(key)
        val startTime = System.currentTimeMillis
        logger.trace(s"Getting value with key ${prefixKey} from Redis cache")
        val f = r.get[T](prefixKey).recoverWith {
          case e: InvalidClassException =>
            logger.warn(s"Deserialization failed, removing $prefixKey from Redis cache", e)
            r.del(prefixKey).map(_ => None)
        }
        f.onSuccess {
          case Some(_) =>
            val duration = System.currentTimeMillis - startTime
            if (duration > slowRedisRequestThresholdMillis) {
              logger.info(s"Retrieving object with $prefixKey from Redis took $duration ms")
            }
        }
        f
      }

      private def k(key: K): String = k(key, cacheKeyPrefix)

      override def get(key: K, loader: K => Future[Option[T]]): Future[Option[T]] = {
        get(key).flatMap {
          case Some(v) => Future.successful(Some(v))
          case None => updateConcurrencyHandler.initiateLoadingIfNotYetRunning(key, loader, this.+, k)
        }
      }

      override def toOption(value: Future[T]): Future[Option[T]] = value.map(Some(_))
    }

    class ByteStringFormatterImpl[T] extends ByteStringFormatter[T] {
      def serialize(data: T): ByteString = {
        val baos = new ByteArrayOutputStream
        val oos = new ObjectOutputStream(baos)
        try {
          oos.writeObject(data)
          ByteString(baos.toByteArray)
        } finally {
          oos.close()
        }
      }

      def deserialize(bs: ByteString): T = {
        val ois = new ObjectInputStream(new ByteArrayInputStream(bs.toArray))
        try {
          ois.readObject().asInstanceOf[T]
        } finally {
          ois.close()
        }
      }
    }

    object ByteStringFormatterLongImpl extends ByteStringFormatter[Long] {
      def serialize(data: Long): ByteString = {
        val baos = new ByteArrayOutputStream
        val oos = new ObjectOutputStream(baos)
        try {
          oos.writeObject(data)
          ByteString(baos.toByteArray)
        } finally {
          oos.close()
        }
      }

      def deserialize(bs: ByteString): Long = {
        val ois = new ObjectInputStream(new ByteArrayInputStream(bs.toArray))
        try {
          ois.readObject().asInstanceOf[Long]
        } finally {
          ois.close()
        }
      }
    }
  }

  class RedisCacheInitializationException(message: String) extends Exception(message) {
    def this(message: String, cause: Throwable) {
      this(message)
      initCause(cause)
    }
  }
}
