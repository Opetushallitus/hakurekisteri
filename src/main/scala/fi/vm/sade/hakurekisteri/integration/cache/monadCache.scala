package fi.vm.sade.hakurekisteri.integration.cache

import java.util.Collections
import java.util.concurrent.Semaphore

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import scala.language.higherKinds

trait MonadCache[F[_], K, T] {

  def +(key: K, f: F[T]): F[_]

  def -(key: K)

  def contains(key: K): Boolean

  def get(key: K): F[T]

  def get(key: K, loader: K => F[Option[T]]): F[Option[T]]

  def toOption(value: F[T]): F[Option[T]]

  protected def k(key: K, prefix:String) = s"${prefix}:${key}"
}

class InMemoryFutureCache[K, T](val exp: Long = 60.minutes.toMillis) extends InMemoryMonadCache[Future, K, T](exp) {
  private val commonLockHandlingSemaphore = new Semaphore(1)
  private val loadingLocks: mutable.Map[K, Semaphore] = TrieMap[K, Semaphore]()
  private val waitingPromises: mutable.Map[K, java.util.List[Promise[Option[T]]]] = TrieMap[K, java.util.List[Promise[Option[T]]]]()

  import scala.concurrent.ExecutionContext.Implicits.global
  override def toOption(value: Future[T]): Future[Option[T]] = value.map(Some(_))

  override def get(key: K, loader: K => Future[Option[T]]): Future[Option[T]] = {
    if (contains(key)) {
      toOption(get(key))
    } else {
      commonLockHandlingSemaphore.acquire()
      try {
        retrieveNewValueWithLoader(key, loader)
      } finally {
        commonLockHandlingSemaphore.release()
      }
    }
  }

  private def retrieveNewValueWithLoader(key: K, loader: K => Future[Option[T]]) = {
    val loadingLockOfKey = loadingLocks.getOrElseUpdate(key, { new Semaphore(1) })
    val promisesOfThisKey = waitingPromises.getOrElseUpdate(key, { createSynchonizedList })
    val newClientPromise: Promise[Option[T]] = Promise[Option[T]]
    promisesOfThisKey.add(newClientPromise)
    if (loadingLockOfKey.availablePermits() > 0) {
      loadingLockOfKey.acquire()
      if (contains(key)) {
        loadingLockOfKey.release()
        toOption(get(key))
      } else {
        val loadingFuture: Future[Option[T]] = loader(key)
        loadingFuture.onComplete { result =>
          commonLockHandlingSemaphore.acquire()
          try {
            if (result.isSuccess) {
              result.get.foreach(foundItem => storeToCache(key, foundItem))
            }
            waitingPromises.remove(key) match {
              case Some(promisesWaitingForResult) => promisesWaitingForResult.asScala.foreach(_.complete(result))
              case None => throw new IllegalStateException(s"Nobody waiting for results of $key , got result $result")
            }
          } finally {
            loadingLockOfKey.release()
            commonLockHandlingSemaphore.release()
          }
        }
        loadingFuture
      }
    } else {
      newClientPromise.future
    }
  }

  private def storeToCache(key: K, found: T): Unit = {
    this.+(key, Future.successful(found))
  }

  private def createSynchonizedList: java.util.List[Promise[Option[T]]] = {
    Collections.synchronizedList(new java.util.ArrayList[Promise[Option[T]]]())
  }
}

case class Cacheable[F[_], T](inserted: Long = Platform.currentTime, accessed: Long = Platform.currentTime, f: F[T])

abstract class InMemoryMonadCache[F[_], K, T](val expirationDurationMillis: Long = 60.minutes.toMillis) extends MonadCache[F, K, T] {

  private var cache: Map[K, Cacheable[F, T]] = Map()

  def +(key: K, f: F[T]): F[_] = {
    val value = cacheable(key, f)
    cache = cache + (key -> value)
    value.f
  }

  def cacheable(key: K, f: F[T]): Cacheable[F, T] = {
    Cacheable[F, T](f = f, accessed = getAccessed(key))
  }

  def -(key: K) = if (cache.contains(key)) cache = cache - key

  def contains(key: K): Boolean = cache.contains(key) && (cache(key).inserted + expirationDurationMillis) > Platform.currentTime

  def get(key: K): F[T] = {
    val cached = cache(key)
    cache = cache + (key -> Cacheable[F, T](inserted = cached.inserted, f = cached.f))
    cached.f
  }

  def get(key: K, loader: K => F[Option[T]]): F[Option[T]] = {
    if (cache.contains(key)) {
      toOption(get(key))
    } else {
        loader(key)
    }
  }

  def inUse(key: K): Boolean = cache.contains(key) && (cache(key).accessed + expirationDurationMillis) > Platform.currentTime

  private def getAccessed(key: K): Long = cache.get(key).map(_.accessed).getOrElse(Platform.currentTime)

  def size: Int = cache.size

  def getCache: Map[K, Cacheable[F, T]] = cache
}
