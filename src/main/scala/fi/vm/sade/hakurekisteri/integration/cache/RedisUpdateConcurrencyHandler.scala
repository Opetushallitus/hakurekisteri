package fi.vm.sade.hakurekisteri.integration.cache

import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiFunction

import redis.{ByteStringFormatter, RedisClient}

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

class RedisUpdateConcurrencyHandler[K, T](val r: RedisClient, limitOfWaitingClientsToLog: Int)(
  implicit val byteStringFormatter: ByteStringFormatter[T],
  implicit val executionContext: ExecutionContext
) {

  private val logger = org.slf4j.LoggerFactory.getLogger(getClass)
  private val waitingPromises: ConcurrentHashMap[K, List[Promise[Option[T]]]] =
    new ConcurrentHashMap[K, List[Promise[Option[T]]]]()

  def initiateLoadingIfNotYetRunning(
    key: K,
    loader: K => Future[Option[T]],
    loadedValueStorer: (K, T) => Future[_],
    keyPrefixer: K => String
  ): Future[Option[T]] = {
    val (newClientPromise, loadIsInProgressAlready) = storePromiseForThisRequest(key)
    val prefixKey = keyPrefixer(key)
    if (loadIsInProgressAlready) {
      logger.debug(s"Load already in progress for key $prefixKey, waiting to be resolved.")
    } else {
      r.get(prefixKey)
        .flatMap {
          case result @ Some(_) =>
            logger.debug(s"Value with $prefixKey had appeared in cache, no need to retrieve it.")
            Future.successful(result)
          case None =>
            logger.debug(s"Starting retrieval of $prefixKey with loader function.")
            retrieveNewValueWithLoader(key, loader, loadedValueStorer)
        }
        .onComplete(resolveWaiters(key, _, waitingPromises.remove(key)))
    }
    newClientPromise.future
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
    val (newClientPromise :: otherPromises): Seq[Promise[Option[T]]] =
      waitingPromises.compute(key, updateFunction)

    val numberWaiting = otherPromises.size + 1
    logger.debug(s"Waiting after adding: $numberWaiting")
    if (numberWaiting > limitOfWaitingClientsToLog) {
      logger.warn(s"Already $numberWaiting clients waiting for value of $key")
    }
    (newClientPromise, otherPromises.nonEmpty)
  }

  private def retrieveNewValueWithLoader(
    key: K,
    loader: K => Future[Option[T]],
    loadedValueStorer: (K, T) => Future[_]
  ): Future[Option[T]] = {
    val loadingFuture = loader(key)
    loadingFuture.onComplete { result =>
      resolveWaiters(key, result, waitingPromises.get(key))
    }

    loadingFuture.flatMap {
      case result @ Some(found) => loadedValueStorer(key, found).map(_ => result)
      case result               => Future.successful(result)
    }
  }

  private def resolveWaiters(
    key: K,
    result: Try[Option[T]],
    promisesWaitingForResult: List[Promise[Option[T]]]
  ): Unit = {
    if (promisesWaitingForResult != null) {
      logger.debug(s"Resolving promises for key $key: ${promisesWaitingForResult.size}")
      promisesWaitingForResult.foreach { _.tryComplete(result) }
    } else {
      logger.debug(s"Nobody waiting for results of $key, got result $result")
    }
  }
}
