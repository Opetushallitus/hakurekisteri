package fi.vm.sade.hakurekisteri.integration.cache

import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiFunction

import redis.{ByteStringFormatter, RedisClient}

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
    val prefixKey = keyPrefixer(key)
    if (loadIsInProgressAlready) {
      logger.debug(s"Load already in progress for key $prefixKey, waiting to be resolved.")
      newClientPromise.future
    } else {
      r.get(prefixKey).onComplete {
        case Success(found@Some(_)) =>
          logger.debug(s"Value with $prefixKey had appeared in cache, no need to retrieve it.")
          resolvePromisesWaitingForValueFromCache(key, Success(found), removeWaiters = true)
        case Success(None) =>
          logger.debug(s"Starting retrieval of $prefixKey with loader function.")
          retrieveNewvalueWithLoader(key, loader, loadedValueStorer)
        case failure@Failure(e) =>
          resolvePromisesWaitingForValueFromCache(key, failure, removeWaiters = true)
      }
      newClientPromise.future
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
                                         loadedValueStorer: (K, Future[T]) => Future[_]): Unit = {
    val loadingFuture = loader(key)
    loadingFuture.onComplete { result =>
      if (result.isSuccess) {
        result.get match {
          case Some(foundItem) =>
            loadedValueStorer(key, Future.successful(foundItem)).onComplete { _ =>
              resolvePromisesWaitingForValueFromCache(key, result, removeWaiters = true)
            }
          case None =>
            resolvePromisesWaitingForValueFromCache(key, result, removeWaiters = true)
        }
      } else {
        resolvePromisesWaitingForValueFromCache(key, result, removeWaiters = true)
      }
      resolvePromisesWaitingForValueFromCache(key, result, removeWaiters = false)
    }
  }

  private def resolvePromisesWaitingForValueFromCache(key: K, result: Try[Option[T]], removeWaiters: Boolean): Unit = {
    val resolveWaiters: List[Promise[Option[T]]] => Unit = { promisesWaitingForResult =>
      if (promisesWaitingForResult != null) {
        logger.debug(s"Resolving promises for key $key: ${promisesWaitingForResult.size}")
        promisesWaitingForResult.foreach { _.tryComplete(result) }
      } else {
        logger.debug(s"Nobody waiting for results of $key, got result $result")
      }
    }
    if (removeWaiters) {
      resolveWaiters(waitingPromises.remove(key))
    } else {
      resolveWaiters(waitingPromises.get(key))
    }
  }
}
