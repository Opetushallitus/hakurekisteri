package fi.vm.sade.hakurekisteri.integration

import fi.vm.sade.hakurekisteri.MockCacheFactory
import fi.vm.sade.hakurekisteri.integration.cache.InMemoryFutureCache
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class FutureCacheSpec extends FlatSpec with Matchers {

  val cacheFactory = MockCacheFactory.get

  def newCache(ttl: Long = 10.seconds.toMillis) = cacheFactory
    .getInstance[String, String](ttl, this.getClass, classOf[String], "moi")
    .asInstanceOf[InMemoryFutureCache[String, String]]

  behavior of "FutureCache"

  val cacheKey = "foo"
  private val cacheEntryValue = "bar"
  val cacheEntry = Future.successful(cacheEntryValue)

  it should "add an entry to cache" in {
    val cache = newCache()

    cache + (cacheKey, cacheEntryValue)

    cache.getCache(cacheKey) should be(cacheEntryValue)
  }

  it should "remove an entry from cache" in {
    val cache = newCache()

    Await.result(cache + (cacheKey, cacheEntryValue), 1.second)

    cache.getCache.size should be(1)

    cache - cacheKey

    cache.getCache.size should be(0)
  }

  it should "return the size of the cache" in {
    val cache = newCache()

    cache + (cacheKey, cacheEntryValue)
    cache + ("foo2", cacheEntryValue + "2")

    cache.size should be(2)
  }

  it should "tell if cache contains a key" in {
    val cache = newCache()

    cache + (cacheKey, cacheEntryValue)

    Await.result(cache.contains(cacheKey), 1.second) should be(true)
  }

  it should "tell if an entry is no longer live" in {
    val cache = newCache(0)

    cache + (cacheKey, cacheEntryValue)

    Await.result(cache.contains(cacheKey), 1.second) should be(false)
  }

  it should "not populate cache with empty value" in {
    val cache = newCache()

    an[NullPointerException] should be thrownBy {
      Await.result(cache.get("lol", _ => Future.successful(None)), 1.second) should be(None)
    }
  }
}
