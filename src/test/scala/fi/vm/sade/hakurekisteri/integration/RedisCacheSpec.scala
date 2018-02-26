package fi.vm.sade.hakurekisteri.integration

import akka.actor.ActorSystem
import fi.vm.sade.hakurekisteri.integration.cache.CacheFactory
import fi.vm.sade.scalaproperties.OphProperties
import fi.vm.sade.utils.tcp.PortChecker
import org.mockito.Mockito.{times, verify, when}
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import redis.embedded.RedisServer

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class RedisCacheSpec extends FlatSpec with Matchers with ActorSystemSupport with BeforeAndAfterAll with DispatchSupport with MockitoSugar {

  val port = PortChecker.findFreeLocalPort
  val redisServer = new RedisServer(port)

  def redisCacheFactory(implicit system:ActorSystem) = CacheFactory.apply(new OphProperties()
    .addDefault("suoritusrekisteri.cache.redis.enabled", "true")
    .addDefault("suoritusrekisteri.cache.redis.host", "localhost")
    .addDefault("suoritusrekisteri.cache.redis.numberOfWaitersToLog", "5")
    .addDefault("suoritusrekisteri.cache.redis.cacheItemLockMaxDurationSeconds", "6")
    .addDefault("suoritusrekisteri.cache.redis.cacheHandlingThreadPoolSize", "3")
    .addDefault("suoritusrekisteri.cache.redis.slowRedisRequestThresholdMillis", "0")
    .addDefault("suoritusrekisteri.cache.redis.port", s"${port}"))(system)

  override def beforeAll() = {
    redisServer.start
  }

  val cacheKey = "foo"
  val cacheEntry = "bar"
  val cacheEntryF = Future.successful(cacheEntry)

  val concurrencyTestLoopCount: Int = 5
  val concurrencyTestParallelRequestCount: Int = 10
  val concurrencyTestResultsWaitDuration: Duration = 10.seconds

  it should "add an entry to cache" in {
   withSystem(
      implicit system => {
        val cache = redisCacheFactory.getInstance[String,String](3.minutes.toMillis, getClass, "prefix1")

        cache + (cacheKey, cacheEntryF)

        Thread.sleep(500)

        Await.result(cache.contains(cacheKey), 1.second) should be(true)

        Await.result(cache get cacheKey, 10.seconds) should be (cacheEntry)
      }
    )
  }

  it should "remove an entry from cache" in {
    withSystem(
      implicit system => {
        val cache = redisCacheFactory.getInstance[String, String](3.minutes.toMillis, getClass, "prefix2")

        cache + (cacheKey, cacheEntryF)

        Thread.sleep(500)

        Await.result(cache.contains(cacheKey), 1.second) should be(true)

        cache - cacheKey

        Thread.sleep(500)

        Await.result(cache.contains(cacheKey), 1.second) should be(false)
      }
    )
  }

  it should "be usable from multiple actor systems" in {
    withSystem(
      implicit system => {
        val cache = redisCacheFactory.getInstance[String,String](3.minutes.toMillis, getClass, "prefix3")

        cache + (cacheKey, cacheEntryF)

        Thread.sleep(500)
      }
    )
    withSystem(
      implicit system => {
        val cache = redisCacheFactory.getInstance[String,String](3.minutes.toMillis, getClass, "prefix3")

        Await.result(cache.contains(cacheKey), 1.second) should be(true)
      }
    )
  }

  it should "use prefixes" in {
    withSystem(
      implicit system => {
        val cache = redisCacheFactory.getInstance[String,String](3.minutes.toMillis, getClass, "prefix4")

        cache + (cacheKey, cacheEntryF)

        Thread.sleep(500)
      }
    )
    withSystem(
      implicit system => {
        val cache4 = redisCacheFactory.getInstance[String,String](3.minutes.toMillis, getClass, "prefix4")
        val cache5 =  redisCacheFactory.getInstance[String,String](3.minutes.toMillis, getClass, "prefix5")

        Await.result(cache5.contains(cacheKey), 1.second) should be(false)
        Await.result(cache4.contains(cacheKey), 1.second) should be(true)
      }
    )
  }

  it should "only do one backend call per missing value with loader accepting get API" in {
    1.to(concurrencyTestLoopCount).foreach { counter =>
      withSystem(
         implicit system => {
           val cache = redisCacheFactory.getInstance[String,String](3.minutes.toMillis, getClass, s"prefixLoaderApi$counter")

           val mockLoader: String => Future[Option[String]] = mock[String => Future[Option[String]]]
           when(mockLoader.apply(cacheKey)).thenAnswer(new Answer[Future[Option[String]]] {
             override def answer(invocation: InvocationOnMock): Future[Option[String]] = {
               Thread.sleep(3)
               Future.successful(Some(cacheEntry))
             }
           })

           val results = 1.to(concurrencyTestParallelRequestCount).par.map { _ =>
             Thread.sleep(10)
             cache.get(cacheKey, mockLoader)
           }.map(Await.result(_, concurrencyTestResultsWaitDuration))
           results should have size concurrencyTestParallelRequestCount
           results.foreach(_ should be(Some(cacheEntry)))

           Thread.sleep(100)
           Await.result(cache.contains(cacheKey), 1.second) should be(true)
           Await.result(cache.get(cacheKey), 1.second) should be (cacheEntry)

           verify(mockLoader, times(1)).apply(cacheKey)
         }
       )
    }
  }

  override def afterAll() = {
    redisServer.stop
  }
}
