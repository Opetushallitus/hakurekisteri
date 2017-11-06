package fi.vm.sade.hakurekisteri.integration

import akka.actor.ActorSystem
import fi.vm.sade.hakurekisteri.integration.cache.CacheFactory
import fi.vm.sade.scalaproperties.OphProperties
import fi.vm.sade.utils.tcp.PortChecker
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Ignore, Matchers}
import redis.embedded.RedisServer

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

/*
 * This test can be run locally to test resis cache implementation.
 * However, currently in Bamboo RedisServer cannot be started
 * due to libc incompatibility issue!
 * /tmp/1509711535309-0/redis-server-2.8.19: /lib64/libc.so.6: version
 * `GLIBC_2.14' not found (required by /tmp/1509711535309-0/redis-server-2.8.19)
 */
@Ignore
class RedisCacheSpec extends FlatSpec with Matchers with ActorSystemSupport with BeforeAndAfterAll {

  val port = PortChecker.findFreeLocalPort
  val redisServer = new RedisServer(port)

  def redisCacheFactory(implicit system:ActorSystem) = CacheFactory.apply(new OphProperties()
    .addDefault("redis_suoritusrekisteri_enabled", "true")
    .addDefault("redis_suoritusrekisteri_host", "localhost")
    .addDefault("redis_suoritusrekisteri_port", s"${port}"))(system)

  override def beforeAll() = {
    redisServer.start
  }

  val cacheKey = "foo"
  val cacheEntry = "bar"
  val cacheEntryF = Future.successful(cacheEntry)

  ignore should "add an entry to cache" in {
    withSystem(
      implicit system => {
        val cache = redisCacheFactory.getInstance[String,String](60, getClass, "prefix1")

        cache + (cacheKey, cacheEntryF)

        Thread.sleep(100)

        cache contains(cacheKey) should be(true)

        Await.result(cache get cacheKey, 10.seconds) should be (cacheEntry)
      }
    )
  }

  ignore should "remove an entry from cache" in {
    withSystem(
      implicit system => {
        val cache = redisCacheFactory.getInstance[String, String](60, getClass, "prefix2")

        cache + (cacheKey, cacheEntryF)

        Thread.sleep(100)

        cache contains(cacheKey) should be(true)

        cache - cacheKey

        Thread.sleep(100)

        cache contains(cacheKey) should be(false)
      }
    )
  }

  ignore should "be usable from multiple actor systems" in {
    withSystem(
      implicit system => {
        val cache = redisCacheFactory.getInstance[String,String](60, getClass, "prefix3")

        cache + (cacheKey, cacheEntryF)

        Thread.sleep(100)
      }
    )
    withSystem(
      implicit system => {
        val cache = redisCacheFactory.getInstance[String,String](60, getClass, "prefix3")

        cache contains(cacheKey) should be(true)
      }
    )
  }

  ignore should "use prefixes" in {
    withSystem(
      implicit system => {
        val cache = redisCacheFactory.getInstance[String,String](60, getClass, "prefix4")

        cache + (cacheKey, cacheEntryF)

        Thread.sleep(100)
      }
    )
    withSystem(
      implicit system => {
        val cache4 = redisCacheFactory.getInstance[String,String](60, getClass, "prefix4")
        val cache5 =  redisCacheFactory.getInstance[String,String](60, getClass, "prefix5")

        cache5 contains(cacheKey) should be(false)
        cache4 contains(cacheKey) should be(true)
      }
    )
  }

  override def afterAll() = {
    redisServer.stop
  }
}
