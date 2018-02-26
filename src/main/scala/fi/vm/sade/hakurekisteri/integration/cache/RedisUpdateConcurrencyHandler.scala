package fi.vm.sade.hakurekisteri.integration.cache

import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiFunction

import redis.{ByteStringFormatter, RedisClient}

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

class RedisUpdateConcurrencyHandler[K, T](val r: RedisClient,
                                          limitOfWaitingClientsToLog: Int,
                                          cacheItemLockMaxDurationSeconds: Int)
                                         (implicit val byteStringFormatter: ByteStringFormatter[T],
                                           implicit val executionContext: ExecutionContext) {

  private val logger = org.slf4j.LoggerFactory.getLogger(getClass)
  private val waitingPromises: ConcurrentHashMap[K, List[Promise[Option[T]]]] = new ConcurrentHashMap[K, List[Promise[Option[T]]]]()

  def initiateLoadingIfNotYetRunning(key: K,
                                     loader: K => Future[Option[T]],
                                     loadedValueStorer: (K, Future[T]) => Future[_],
                                     keyPrefixer: K => String): Future[Option[T]] = {
    val (newClientPromise, loadIsInProgressAlready) = storePromiseForThisRequest(key)
    if (loadIsInProgressAlready) {
      newClientPromise.future
    } else {
      val prefixKey = keyPrefixer(key)
      val lockKey = prefixKey + "-lock"
      r.set(lockKey, "LOCKED", exSeconds = Some(cacheItemLockMaxDurationSeconds), NX = true).flatMap { lockObtained =>
        if (lockObtained) {
          logger.debug(s"Successfully acquired update lock of $lockKey")
          r.get(prefixKey).onComplete {
            case Success(found@Some(_)) =>
              logger.debug(s"Value with $prefixKey had appeared in cache, no need to retrieve it.")
              resolvePromisesWaitingForValueFromCache(key, Success(found))
              removeCacheUpdateLock(lockKey)
            case Success(None) =>
              logger.debug(s"Starting retrieval of $prefixKey with loader function.")
              retrieveNewvalueWithLoader(key, loader, loadedValueStorer, lockKey)
            case Failure(e) => newClientPromise.failure(e)
          }
        } else {
          logger.debug(s"Could not acquire $lockKey, waiting patiently for my promise to be resolved. We're ${waitingPromises.asScala.get(key).map(_.size).getOrElse(0)} in total waiting")
        }
        newClientPromise.future
      }.recoverWith { case e =>
        logger.error(s"Error when obtaining lock $lockKey", e)
        Future.failed(e)
      }
    }
  }

  private def storePromiseForThisRequest(key: K): (Promise[Option[T]], Boolean) = {
    logger.debug("Adding new client promise:")
    val updateFunction = new BiFunction[K, List[Promise[Option[T]]], List[Promise[Option[T]]]] {
      override def apply(key: K, promises: List[Promise[Option[T]]]): List[Promise[Option[T]]] = {
        val myPromise = Promise[Option[T]]
        if (promises == null) {
          List(myPromise)
        } else {
          myPromise :: promises
        }
      }
    }
    val (newClientPromise :: otherPromises): Seq[Promise[Option[T]]] = waitingPromises.compute(key, updateFunction)

    val numberWaiting = otherPromises.size + 1
    logger.debug(s"Waiting after adding: $numberWaiting")
    if (numberWaiting > limitOfWaitingClientsToLog) {
      logger.warn(s"Already $numberWaiting clients waiting for value of $key")
    }
    (newClientPromise, otherPromises.nonEmpty)
  }

  private def retrieveNewvalueWithLoader(key: K,
                                         loader: K => Future[Option[T]],
                                         loadedValueStorer: (K, Future[T]) => Future[_],
                                         lockKey: String): Unit = {
    val loadingFuture = loader(key)
    loadingFuture.onComplete { result =>
      if (result.isSuccess) {
        result.get match {
          case Some(foundItem) =>
            loadedValueStorer(key, Future.successful(foundItem)).onComplete { _ =>
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
    val updateFunction = new BiFunction[K, List[Promise[Option[T]]], List[Promise[Option[T]]]] {
      override def apply(key: K, promisesWaitingForResult: List[Promise[Option[T]]]): List[Promise[Option[T]]] = {
        if (promisesWaitingForResult != null) {
          logger.debug(s"Resolving promises for key $key: ${promisesWaitingForResult.size}")
          promisesWaitingForResult.foreach { _.complete(result) }
        } else {
          if (failIfNobodyWaiting) {
            throw new IllegalStateException(s"Nobody waiting for results of $key , got result $result")
          } else {
            logger.debug(s"Nobody waiting for results of $key, got result $result")
          }
        }
        null
      }
    }

    waitingPromises.compute(key, updateFunction)
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
}
