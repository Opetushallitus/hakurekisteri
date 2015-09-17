package fi.vm.sade.hakurekisteri.integration

import scala.compat.Platform
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.higherKinds

case class Cacheable[F[_], T](inserted: Long = Platform.currentTime, accessed: Long = Platform.currentTime, f: F[T])

class FutureCache[K, T](val exp: Long = 60.minutes.toMillis) extends MonadCache[Future, K, T](exp)


class MonadCache[F[_], K, T](val expirationDurationMillis: Long = 60.minutes.toMillis) {

  private var cache: Map[K, Cacheable[F, T]] = Map()

  def +(key: K, f: F[T]) = cache = cache + (key -> cacheable(key, f))

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

  def inUse(key: K): Boolean = cache.contains(key) && (cache(key).accessed + expirationDurationMillis) > Platform.currentTime

  private def getAccessed(key: K): Long = cache.get(key).map(_.accessed).getOrElse(Platform.currentTime)

  def size: Int = cache.size

  def getCache: Map[K, Cacheable[F, T]] = cache

}