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
                        cacheKeyPrefix:String)(implicit m: Manifest[T]): MonadCache[Future, K, T] = {
    getInstance[K, T](expirationDurationMillis, prefix(clazz, cacheKeyPrefix))
  }

  def getInstance[K, T <: Serializable](expirationDurationMillis:Long,
                        cacheKeyPrefix:String)(implicit m: Manifest[T]): MonadCache[Future, K, T]
}

object CacheFactory {
  def apply(config: OphProperties)(implicit system: ActorSystem): CacheFactory = config.getOrElse("suoritusrekisteri.cache.redis.enabled", "false") match {
    case p if "TRUE".equalsIgnoreCase(p) => new RedisCacheFactory(config)
    case _ => new InMemoryCacheFactory
  }

  class InMemoryCacheFactory extends CacheFactory {
    override def getInstance[K, T](expirationDurationMillis: Long, cacheKeyPrefix: String)(implicit m: Manifest[T]): InMemoryFutureCache[K, T] = {
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

    override def getInstance[K, T](expirationDurationMillis: Long, cacheKeyPrefix: String)(implicit m: Manifest[T]) = {
      new RedisCache[K, T](
        r,
        expirationDurationMillis,
        cacheKeyPrefix,
        config.getProperty("suoritusrekisteri.cache.redis.numberOfWaitersToLog").toInt,
        config.getProperty("suoritusrekisteri.cache.redis.cacheHandlingThreadPoolSize").toInt,
        config.getProperty("suoritusrekisteri.cache.redis.slowRedisRequestThresholdMillis").toInt)
    }

    class RedisCache[K, T](val r: RedisClient,
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
      private val newVersion = {
        val clazz = m.runtimeClass
        val streamClass = java.io.ObjectStreamClass.lookup(clazz)
        streamClass.getSerialVersionUID
      }

      init()

      private def init(): Unit = {
        val f: Future[Boolean] = getVersion.flatMap {
          case Some(oldVersion) =>
            clearCacheIfVersionHasChanged(oldVersion)
          case None =>
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

      private def clearCacheIfVersionHasChanged(oldVersion: Long): Future[Boolean] = {
        if (oldVersion != newVersion) {
          logger.info(s"Serial version UID has changed from $oldVersion to $newVersion. Deleting all keys.")

          /*var cursor = -1
          while (cursor != 0) {
            cursor = Await.result(deletingScan(cursor,  s"${cacheKeyPrefix}:*"), 1.minute)
          }*/
          deleteKeysSlow(s"${cacheKeyPrefix}:*").flatMap { _ =>
            setVersion(newVersion)
          }
        } else {
          logger.info(s"Serial version UID has not changed, is still $newVersion.")
          Future.successful(true)
        }
      }

      private def deleteKeysSlow(pattern: String): Future[Long] = {
        r.keys(pattern).flatMap { keys =>
          if (keys.nonEmpty) {
            logger.info(s"Deleting ${keys.length} keys matching pattern $pattern")
            r.del(keys: _*)
          } else {
            Future.successful(0l)
          }
        }
      }

      private def deletingScan(cursor: Int, pattern: String): Future[Int] = {
        r.scan(cursor = cursor, count = Some(1000), matchGlob = Some(pattern))
          .flatMap { result =>
            val keys = result.data
            logger.info(s"Scan returned ${keys.length} matching keys at index ${result.index} for pattern ${pattern}")
            val fInner = if (keys.nonEmpty) {
              logger.info(s"Deleting ${keys.mkString(",")}")
              r.del(keys: _*)
            } else {
              Future.successful(Unit)
            }
            fInner.map { _ => result.index }
          }
      }

      private def setVersion(version: Long): Future[Boolean] = {
        logger.warn(s"Storing version value $version to Redis cache with key $versionPrefixKey")
        val f: Future[Boolean] = r.set[Long](versionPrefixKey, version, Some(expirationDurationMillis))
        f.onSuccess{case b: Boolean =>
          if (!b) {
            throw new RuntimeException(s"Failed to store version in Redis cache with prefix $cacheKeyPrefix")
        }}
        f
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
