package fi.vm.sade.hakurekisteri.integration

import scala.compat.Platform
import scala.concurrent.Future
import scala.concurrent.duration._

case class Cacheable[T](inserted: Long = Platform.currentTime, f: Future[T])

class FutureCache[K, T](val expirationDurationMillis: Long = 60.minutes.toMillis) {

  var cache: Map[K, Cacheable[T]] = Map()

  def contains(key: K): Boolean = cache.contains(key) && cache(key).inserted + expirationDurationMillis > Platform.currentTime

  def get(key: K): Future[T] = cache(key).f

  def +(key: K, f: Future[T]) = cache = cache + (key -> Cacheable(f = f))

}
