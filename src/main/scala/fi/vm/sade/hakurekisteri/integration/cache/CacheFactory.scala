package fi.vm.sade.hakurekisteri.integration.cache

import java.io._
import java.util.Collections
import java.util.concurrent.locks.ReentrantLock

import akka.actor.ActorSystem
import akka.util.ByteString
import fi.vm.sade.properties.OphProperties
import fi.vm.sade.utils.Timer
import org.apache.commons.io.IOUtils
import redis.{ByteStringFormatter, RedisClient}

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}
import scala.util.{Failure, Success, Try}

trait CacheFactory {

  val defaultExpirationDuration: Long = 60.minutes.toMillis
  def prefix(clazz:Class[_], prefix:String) = s"${clazz.getName}:${prefix}"

  def getInstance[K, T](expirationDurationMillis:Long,
                        clazz:Class[_],
                        cacheKeyPrefix:String):MonadCache[Future, K, T] = getInstance[K, T](expirationDurationMillis, prefix(clazz, cacheKeyPrefix))

  def getInstance[K, T](expirationDurationMillis:Long,
                        cacheKeyPrefix:String):MonadCache[Future, K, T]
}

object CacheFactory {
  def apply(config: OphProperties)(implicit system: ActorSystem): CacheFactory = config.getOrElse("suoritusrekisteri.cache.redis.enabled", "false") match {
    case p if "TRUE".equalsIgnoreCase(p) => new RedisCacheFactory(config)
    case _ => new InMemoryCacheFactory
  }

  class InMemoryCacheFactory extends CacheFactory {
    override def getInstance[K, T](expirationDurationMillis: Long, cacheKeyPrefix: String) = new InMemoryFutureCache[K, T](expirationDurationMillis)
  }

  class RedisCacheFactory(config: OphProperties)(implicit system: ActorSystem) extends CacheFactory {

    val r = {
      val host = config.getOrElse("suoritusrekisteri.cache.redis.host", "")
      if ("".equals(host)) throw new RuntimeException(s"No configuration for Redis host found")
      val port = config.getOrElse("suoritusrekisteri.cache.redis.port", "6379").toInt
      org.slf4j.LoggerFactory.getLogger(getClass).info(s"Using redis cache ${host}:${port}")
      RedisClient(host = host, port = port)
    }

    override def getInstance[K, T](expirationDurationMillis: Long, cacheKeyPrefix: String) = new RedisCache[K, T](
      r,
      expirationDurationMillis,
      cacheKeyPrefix,
      config.getProperty("suoritusrekisteri.cache.redis.numberOfWaitersToLog").toInt)

    class RedisCache[K, T](val r: RedisClient,
                           val expirationDurationMillis: Long,
                           val cacheKeyPrefix: String,
                           limitOfWaitingClientsToLog: Int) extends MonadCache[Future, K, T] {

      val logger = org.slf4j.LoggerFactory.getLogger(getClass)
      private val waitingPromisesHandlingLock = new ReentrantLock(true)
      private val waitingPromises: mutable.Map[K, java.util.List[Promise[Option[T]]]] = TrieMap[K, java.util.List[Promise[Option[T]]]]()

      import scala.concurrent.ExecutionContext.Implicits.global

      implicit val byteStringFormatter = new ByteStringFormatterImpl[T]

      def +(key: K, f: Future[T]): Future[_] = f flatMap {
        case t =>
          val prefixKey = k(key)
          logger.debug(s"Adding value with key ${prefixKey} to Redis cache")
          r.set[T](prefixKey, t, pxMilliseconds = Some(expirationDurationMillis))
      }

      def -(key: K): Unit = r.del(k(key))

      def contains(key: K): Boolean = {
        Timer.timed(s"Checking contains $cacheKeyPrefix:$key from Redis", 100) {
          Await.result(r.exists(k(key)), 60.seconds)
        }
      }

      def get(key: K): Future[T] = {
        val prefixKey = k(key)
        val startTime = System.currentTimeMillis
        logger.debug(s"Getting value with key ${prefixKey} from Redis cache")
        r.get[T](prefixKey).collect {
          case Some(x) =>
            val duration = System.currentTimeMillis - startTime
            if (duration > 100) {
              logger.info(s"Retrieving object with $prefixKey from Redis took $duration ms")
            }
            x
        }
      }

      private def k(key: K): String = k(key, cacheKeyPrefix)

      override def get(key: K, loader: K => Future[Option[T]]): Future[Option[T]] = {
        r.exists(k(key)).flatMap(keyIsIncache =>
          if (keyIsIncache) {
            toOption(get(key))
          } else {
            lockWaiterBookkeeping()
            try {
              initiateLoadingIfNotYetRunning(key, loader)
            } finally {
              releaseWaitingBookkeeping()
            }
          })
      }

      private def lockWaiterBookkeeping(): Unit = {
        waitingPromisesHandlingLock.lock()
        if (waitingPromisesHandlingLock.getHoldCount != 1) {
          throw new IllegalStateException(s"After locking, waitingPromisesHandlingLock.getHoldCount == " +
            s"${waitingPromisesHandlingLock.getHoldCount} – this should never happen.")
        }
      }

      private def releaseWaitingBookkeeping(): Unit = {
        if (waitingPromisesHandlingLock.getHoldCount != 1) {
          throw new IllegalStateException(s"Before unlocking, waitingPromisesHandlingLock.getHoldCount == " +
            s"${waitingPromisesHandlingLock.getHoldCount} – this should never happen.")
        }
        waitingPromisesHandlingLock.unlock()
      }

      private def initiateLoadingIfNotYetRunning(key: K, loader: K => Future[Option[T]]): Future[Option[T]] = {
        val promisesOfThisKey = waitingPromises.getOrElseUpdate(key, { createSynchonizedList })
        val newClientPromise: Promise[Option[T]] = Promise[Option[T]]
        logger.debug("Adding new client promise:")
        promisesOfThisKey.add(newClientPromise)
        val numberWaiting = promisesOfThisKey.size
        logger.debug(s"Waiting after adding: $numberWaiting")
        if (numberWaiting > limitOfWaitingClientsToLog) {
          logger.warn(s"Already $numberWaiting clients waiting for value of $key")
        }

        val prefixKey = k(key)
        val lockKey = prefixKey + "-lock"
        r.set(lockKey, "LOCKED", exSeconds = Some(60), NX = true).flatMap { lockObtained =>
          if (lockObtained) {
            logger.debug(s"Successfully acquired update lock of $lockKey")
            r.get(prefixKey).onComplete {
              case Success(found@Some(_)) =>
                logger.debug(s"Value with $prefixKey had appeared in cache, no need to retrieve it.")
                resolvePromisesWaitingForValueFromCache(key, Success(found))
                removeCacheUpdateLock(lockKey)
              case Success(None) =>
                logger.debug(s"Starting retrieval of $prefixKey with loader function.")
                retrieveNewvalueWithLoader(key, loader, lockKey)
              case Failure(e) => newClientPromise.failure(e)
            }
          } else {
            logger.debug(s"Could not acquire $lockKey, waiting patiently for my promise to be resolved. We're ${promisesOfThisKey.size} in total waiting")
          }
          newClientPromise.future
        }.recoverWith { case e =>
            logger.error(s"Error when obtaining lock $lockKey", e)
            Future.failed(e)
        }
      }

      private def retrieveNewvalueWithLoader(key: K, loader: K => Future[Option[T]], lockKey: String): Unit = {
        val loadingFuture = loader(key)
        loadingFuture.onComplete { result =>
          if (result.isSuccess) {
            result.get match {
              case Some(foundItem) =>
                this.+(key, Future.successful(foundItem)).onComplete { _ =>
                  resolvePromisesWaitingForValueFromCache(key, result, failIfNobodyWaiting = false)
                  removeCacheUpdateLock(lockKey)
                }
              case None =>
                removeCacheUpdateLock(lockKey)
            }
          } else {
            removeCacheUpdateLock(lockKey)
          }
          resolvePromisesWaitingForValueFromCache(key, result)
        }
      }

      private def resolvePromisesWaitingForValueFromCache(key: K, result: Try[Option[T]], failIfNobodyWaiting: Boolean = true): Unit = {
        lockWaiterBookkeeping()
        try {
          waitingPromises.remove(key) match {
            case Some(promisesWaitingForResult) =>
              logger.debug(s"Resolving promises for key $key: ${promisesWaitingForResult.size()}")
              promisesWaitingForResult.asScala.foreach {
                _.complete(result)
              }
            case None =>
              if (failIfNobodyWaiting) {
                throw new IllegalStateException(s"Nobody waiting for results of $key , got result $result")
              } else {
                logger.debug(s"Nobody waiting for results of $key, got result $result")
              }
          }
        } finally {
          releaseWaitingBookkeeping()
        }
      }

      private def removeCacheUpdateLock(lockKey: String): Unit = {
        val removalOfLock = r.del(lockKey)
        removalOfLock.onSuccess { case _ =>
          logger.debug(s"Successfully removed update lock $lockKey")
        }
        removalOfLock.onFailure { case e: Throwable =>
          logger.error(s"Could not remove update lock $lockKey", e)
        }
      }

      override def toOption(value: Future[T]): Future[Option[T]] = value.map(Some(_))

      private def createSynchonizedList: java.util.List[Promise[Option[T]]] = {
        Collections.synchronizedList(new java.util.ArrayList[Promise[Option[T]]]())
      }
    }

    class ByteStringFormatterImpl[T] extends ByteStringFormatter[T] {

      private def close(c: Try[Closeable]) = c.foreach(IOUtils.closeQuietly(_))

      private def resultOrFailure[T](t: Try[T]): T = t match {
        case Success(s) => s
        case Failure(t) => throw t
      }

      private def tryFinally[T](t: Try[T], resources: Try[Closeable]*) = try {
        resultOrFailure(t)
      } finally {
        resources.foreach(close)
      }

      def serialize(data: T): ByteString = {
        val baos = new ByteArrayOutputStream
        val oos = Try(new ObjectOutputStream(baos))

        def ser: Try[ByteString] = oos.map { o =>
          o.writeObject(data)
          ByteString(baos.toByteArray)
        }

        tryFinally[ByteString](ser, oos)
      }

      def deserialize(bs: ByteString): T = {
        val ois = Try(new ObjectInputStream(new ByteArrayInputStream(bs.toArray)))

        def des: Try[T] = ois.map(_.readObject.asInstanceOf[T])

        tryFinally[T](des, ois)
      }
    }

  }
}
