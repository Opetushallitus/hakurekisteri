package fi.vm.sade.hakurekisteri.integration

import scala.compat.Platform
import scala.concurrent.Future
import scala.concurrent.duration._

case class Cacheable[T](inserted: Long = Platform.currentTime, accessed: Long = Platform.currentTime, f: Future[T])

class FutureCache[K, T](val expirationDurationMillis: Long = 60.minutes.toMillis) {

  private var cache: Map[K, Cacheable[T]] = Map()

  def +(key: K, f: Future[T]) = cache = cache + (key -> Cacheable(f = f, accessed = getAccessed(key)))

  def -(key: K) = if (cache.contains(key)) cache = cache - key

  def contains(key: K): Boolean = cache.contains(key) && (cache(key).inserted + expirationDurationMillis) > Platform.currentTime

  def get(key: K): Future[T] = {
    val cached = cache(key)
    cache = cache + (key -> Cacheable(inserted = cached.inserted, f = cached.f))
    cached.f
  }

  def inUse(key: K): Boolean = cache.contains(key) && (cache(key).accessed + expirationDurationMillis) > Platform.currentTime

  private def getAccessed(key: K): Long = cache.get(key).map(_.accessed).getOrElse(Platform.currentTime)

}
