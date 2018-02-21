package fi.vm.sade.hakurekisteri.integration.cache

import java.util.Collections
import java.util.concurrent.locks.ReentrantLock

import redis.{ByteStringFormatter, RedisClient}

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

class RedisUpdateConcurrencyHandler[K, T](val r: RedisClient,
                                          limitOfWaitingClientsToLog: Int,
                                          cacheItemLockMaxDurationSeconds: Int)
                                         (implicit val byteStringFormatter: ByteStringFormatter[T],
                                           implicit val executionContext: ExecutionContext) {

  private val logger = org.slf4j.LoggerFactory.getLogger(getClass)
  private val waitingPromisesHandlingLock = new ReentrantLock(true)
  private val waitingPromises: mutable.Map[K, java.util.List[Promise[Option[T]]]] = TrieMap[K, java.util.List[Promise[Option[T]]]]()

  def initiateLoadingIfNotYetRunning(key: K,
                                     loader: K => Future[Option[T]],
                                     loadedValueStorer: (K, Future[T]) => Future[_],
                                     keyPrefixer: K => String): Future[Option[T]] = {
    lockWaiterBookkeeping()
    try {
      val newClientPromise = storePromiseForThisRequest(key)
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
          logger.debug(s"Could not acquire $lockKey, waiting patiently for my promise to be resolved. We're ${waitingPromises.get(key).map(_.size).getOrElse(0)} in total waiting")
        }
        newClientPromise.future
      }.recoverWith { case e =>
        logger.error(s"Error when obtaining lock $lockKey", e)
        Future.failed(e)
      }
    } finally {
      releaseWaitingBookkeeping()
    }
  }

  private def storePromiseForThisRequest(key: K): Promise[Option[T]] = {
    val promisesOfThisKey = waitingPromises.getOrElseUpdate(key, {
      createSynchonizedList
    })
    val newClientPromise: Promise[Option[T]] = Promise[Option[T]]
    logger.debug("Adding new client promise:")
    promisesOfThisKey.add(newClientPromise)
    val numberWaiting = promisesOfThisKey.size
    logger.debug(s"Waiting after adding: $numberWaiting")
    if (numberWaiting > limitOfWaitingClientsToLog) {
      logger.warn(s"Already $numberWaiting clients waiting for value of $key")
    }
    newClientPromise
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

  private def createSynchonizedList: java.util.List[Promise[Option[T]]] = {
    Collections.synchronizedList(new java.util.ArrayList[Promise[Option[T]]]())
  }
}
