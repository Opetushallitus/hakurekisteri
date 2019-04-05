package fi.vm.sade.hakurekisteri.integration

import fi.vm.sade.hakurekisteri.MockCacheFactory
import fi.vm.sade.hakurekisteri.integration.cache.InMemoryFutureCache
import org.scalatest.{FlatSpec, Matchers}

import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class FutureCacheSpec extends FlatSpec with Matchers {

  val cacheFactory = MockCacheFactory.get

  def newCache(ttl: Long = 10.seconds.toMillis) = cacheFactory.getInstance[String, String](ttl, this.getClass, classOf[String], "moi")
    .asInstanceOf[InMemoryFutureCache[String,String]]

  behavior of "FutureCache"

  val cacheKey = "foo"
  private val cacheEntryValue = "bar"
  val cacheEntry = Future.successful(cacheEntryValue)

  it should "add an entry to cache" in {
    val cache = newCache()

    cache + (cacheKey, cacheEntryValue)

    Await.result(cache.getCache(cacheKey).f, 1.second) should be (cacheEntryValue)
  }

  it should "set inserted time for the cached entry" in {
    val cache = newCache()

    cache + (cacheKey, cacheEntryValue)

    cache.getCache(cacheKey).inserted should be <= Platform.currentTime
  }

  it should "update inserted time for an existing entry during add" in {
    val cache = newCache()

    cache + (cacheKey, cacheEntryValue)

    val inserted = cache.getCache(cacheKey).inserted

    Thread.sleep(100)

    cache + (cacheKey, cacheEntryValue)

    cache.getCache(cacheKey).inserted should be > inserted
  }

  it should "remove an entry from cache" in {
    val cache = newCache()

    cache + (cacheKey, cacheEntryValue)

    cache - cacheKey

    cache.getCache.size should be (0)
  }

  it should "return the size of the cache" in {
    val cache = newCache()

    cache + (cacheKey, cacheEntryValue)
    cache + ("foo2", cacheEntryValue + "2")

    cache.size should be (2)
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

}
