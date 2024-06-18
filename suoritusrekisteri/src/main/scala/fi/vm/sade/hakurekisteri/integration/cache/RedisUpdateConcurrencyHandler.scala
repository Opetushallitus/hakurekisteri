package fi.vm.sade.hakurekisteri.integration.cache

import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiFunction
import redis.{ByteStringFormatter, RedisClient}

import scala.concurrent.{ExecutionContext, Future, Promise}

case class LoaderInfo(started: Long, needsLoaderActivation: Boolean)

class RedisUpdateConcurrencyHandler[K, T](
  val r: RedisClient,
  limitOfWaitingClientsToLog: Int,
  loaderTimeoutMillis: Long
)(
  implicit val byteStringFormatter: ByteStringFormatter[T],
  implicit val executionContext: ExecutionContext
) {

  private val logger = org.slf4j.LoggerFactory.getLogger(getClass)
  private val waitingPromises: ConcurrentHashMap[K, (LoaderInfo, List[Promise[Option[T]]])] =
    new ConcurrentHashMap[K, (LoaderInfo, List[Promise[Option[T]]])]()

  private def loaderHasTakenTooLong(start: Long): Boolean =
    start < (System.currentTimeMillis() - loaderTimeoutMillis)

  def getValueAndStartLoaderIfNeeded(
    key: K,
    loader: K => Future[Option[T]],
    loadedValueStorer: (K, T) => Future[_],
    keyPrefixer: K => String
  ): Future[Option[T]] = {

    val prefixKey = keyPrefixer(key)

    val newPromise = storePromise(key) match {
      case (newPromise, needsNewLoader) if needsNewLoader =>
        logger.debug(s"Starting retrieval of $prefixKey with loader function.")
        retrieveNewValueWithLoader(key, loader, loadedValueStorer)
          .onComplete(result => {
            val promisesWaitingForResult = waitingPromises.remove(key)
            if (promisesWaitingForResult != null) {
              logger.info(
                s"Loader finished for key $prefixKey, resolving ${promisesWaitingForResult._2.size} promise(s)"
              )
              promisesWaitingForResult._2.foreach { _.tryComplete(result) }
            } else {
              logger.warn(
                s"Loader finished for key $prefixKey, but nobody is waiting for results. This might signal a problem."
              )
            }
          })
        newPromise
      case (newPromise, _) =>
        logger.debug(s"Load already in progress for key $prefixKey, waiting to be resolved.")
        newPromise
    }

    newPromise.future
  }

  private def storePromise(key: K): (Promise[Option[T]], Boolean) = {
    val updateFunction = new BiFunction[
      K,
      (LoaderInfo, List[Promise[Option[T]]]),
      (LoaderInfo, List[Promise[Option[T]]])
    ] {
      override def apply(
        key: K,
        current: (LoaderInfo, List[Promise[Option[T]]])
      ): (LoaderInfo, List[Promise[Option[T]]]) = {
        val myPromise = Promise[Option[T]]
        if (current == null) {
          (LoaderInfo(System.currentTimeMillis(), needsLoaderActivation = true), List(myPromise))
        } else if (loaderHasTakenTooLong(current._1.started) && !current._1.needsLoaderActivation) {
          logger.warn(
            s"Loader for key $key has been active for ${System.currentTimeMillis() - current._1.started} " +
              s"milliseconds, which is over the config value of $loaderTimeoutMillis. " +
              s"A new loader should be started."
          )
          (
            LoaderInfo(System.currentTimeMillis(), needsLoaderActivation = true),
            myPromise :: current._2
          )
        } else {
          (LoaderInfo(current._1.started, needsLoaderActivation = false), myPromise :: current._2)
        }
      }
    }

    val (needsNewLoader, newClientPromise, numberWaiting): (Boolean, Promise[Option[T]], Int) = {
      waitingPromises.compute(key, updateFunction) match {
        case (loaderInfo, promises) =>
          (loaderInfo.needsLoaderActivation, promises.head, promises.size)
      }
    }

    logger.debug(s"Waiting after adding: $numberWaiting")
    if (numberWaiting > limitOfWaitingClientsToLog) {
      logger.warn(s"Already $numberWaiting clients waiting for value of $key")
    }
    (newClientPromise, needsNewLoader)
  }

  private def retrieveNewValueWithLoader(
    key: K,
    loader: K => Future[Option[T]],
    loadedValueStorer: (K, T) => Future[_]
  ): Future[Option[T]] = {
    val loadingFuture = loader(key)
    loadingFuture.flatMap {
      case result @ Some(found) => loadedValueStorer(key, found).map(_ => result)
      case result               => Future.successful(result)
    }
  }
}
