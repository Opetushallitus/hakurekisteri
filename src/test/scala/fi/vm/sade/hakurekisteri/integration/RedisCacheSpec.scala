package fi.vm.sade.hakurekisteri.integration

import akka.actor.ActorSystem
import fi.vm.sade.hakurekisteri.integration.cache.{CacheFactory, MonadCache}
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
  private val stringLoader: String => Future[Nothing] = (_: String) => Future.failed(new RuntimeException("should not be called"))

  private val javaStringSerialVersionUID: Long = -6849794470754667710l
  private val throwableSerialVersionUID: Long = -3042686055658047285l

  it should "add an entry to cache" in {
   withSystem(
      implicit system => {
        val cache = redisCacheFactory.getInstance[String,String](3.minutes.toMillis, getClass, "prefix1")

        cache + (cacheKey, cacheEntry)

        Thread.sleep(500)

        cache.shouldContain(cacheKey)

        Await.result(cache.get(cacheKey, stringLoader), 10.seconds) should be (Some(cacheEntry))
      }
    )
  }

  it should "remove an entry from cache" in {
    withSystem(
      implicit system => {
        val cache = redisCacheFactory.getInstance[String, String](3.minutes.toMillis, getClass, "prefix2")

        cache + (cacheKey, cacheEntry)

        Thread.sleep(500)

        cache.shouldContain(cacheKey)

        cache - cacheKey

        Thread.sleep(500)

        cache.shouldNotContain(cacheKey)
      }
    )
  }

  it should "be usable from multiple actor systems" in {
    withSystem(
      implicit system => {
        val cache = redisCacheFactory.getInstance[String,String](3.minutes.toMillis, getClass, "prefix3")

        cache + (cacheKey, cacheEntry)

        Thread.sleep(500)
      }
    )
    withSystem(
      implicit system => {
        val cache = redisCacheFactory.getInstance[String,String](3.minutes.toMillis, getClass, "prefix3")

        cache.shouldContain(cacheKey)
      }
    )
  }

  it should "use prefixes" in {
    withSystem(
      implicit system => {
        val cache = redisCacheFactory.getInstance[String,String](3.minutes.toMillis, getClass, "prefix4")

        cache + (cacheKey, cacheEntry)

        Thread.sleep(500)
      }
    )
    withSystem(
      implicit system => {
        val cache4 = redisCacheFactory.getInstance[String,String](3.minutes.toMillis, getClass, "prefix4")
        val cache5 =  redisCacheFactory.getInstance[String,String](3.minutes.toMillis, getClass, "prefix5")

        cache5.shouldNotContain(cacheKey)
        cache4.shouldContain(cacheKey)
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
           cache.shouldContain(cacheKey)
           Await.result(cache.get(cacheKey, stringLoader), 1.second) should be (Some(cacheEntry))

           verify(mockLoader, times(1)).apply(cacheKey)
         }
       )
    }
  }

  it should "store version to cache" in {
    withSystem(
      implicit system => {
        val cache = redisCacheFactory.getInstance[String,String](3.minutes.toMillis, getClass, "prefix6")

        cache + (cacheKey, cacheEntry)

        Thread.sleep(500)

        cache.shouldContain("version")
        Await.result(cache.getVersion, 1.second) should be(Some(javaStringSerialVersionUID))
      }
    )
  }

  it should "update version in cache when it changes" in {
    withSystem(
      implicit system => {
        val cacheOfStrings = redisCacheFactory.getInstance[String,String](3.minutes.toMillis, getClass, "prefix7")

        cacheOfStrings + (cacheKey, cacheEntry)

        Thread.sleep(500)

        cacheOfStrings.shouldContain("version")

        Await.result(cacheOfStrings.getVersion, 1.second) shouldBe Some(javaStringSerialVersionUID)

        val cacheOfThrowables = redisCacheFactory.getInstance[String,Throwable](3.minutes.toMillis, getClass, "prefix7")

        Thread.sleep(500)

        Await.result(cacheOfStrings.getVersion, 1.second) should be(Some(throwableSerialVersionUID))
        Await.result(cacheOfThrowables.getVersion, 1.second) should be(Some(throwableSerialVersionUID))
      }
    )
  }

  it should "clear cache when the version changes" in {
    withSystem(
      implicit system => {
        val cacheOfStrings = redisCacheFactory.getInstance[String,String](3.minutes.toMillis, getClass, "prefix8")

        cacheOfStrings + (cacheKey, cacheEntry)

        Thread.sleep(500)

        cacheOfStrings.shouldContain(cacheKey)

        val cacheOfThrowables = redisCacheFactory.getInstance[String,Throwable](3.minutes.toMillis, getClass, "prefix8")

        Thread.sleep(500)

        cacheOfStrings.shouldNotContain(cacheKey)
        cacheOfThrowables.shouldNotContain(cacheKey)
      }
    )
  }

  it should "not clear cache when creating a new instance with same version" in {
    withSystem(
      implicit system => {
        val cache1 = redisCacheFactory.getInstance[String,String](3.minutes.toMillis, getClass, "prefix9")

        cache1 + (cacheKey, cacheEntry)

        Thread.sleep(500)

        cache1.shouldContain(cacheKey)

        val cache2 = redisCacheFactory.getInstance[String,String](3.minutes.toMillis, getClass, "prefix9")

        Thread.sleep(500)

        cache1.shouldContain(cacheKey)
        cache2.shouldContain(cacheKey)
        Await.result(cache1.get(cacheKey, stringLoader), 1.second) should be(Await.result(cache2.get(cacheKey, stringLoader), 1.second))
      }
    )
  }

  it should "not clear other caches when one cache changes" in {
    withSystem(
      implicit system => {
        val cacheOfStrings1  = redisCacheFactory.getInstance[String,String](3.minutes.toMillis, getClass, "prefix10")
        val cacheOfStrings2 = redisCacheFactory.getInstance[String,String](3.minutes.toMillis, getClass, "prefix11")

        cacheOfStrings1 + (cacheKey, cacheEntry)
        val anotherKey = "anotherkey"
        cacheOfStrings2 + (anotherKey, cacheEntry)

        Thread.sleep(500)

        cacheOfStrings1.shouldContain(cacheKey)
        cacheOfStrings2.shouldContain(anotherKey)
        cacheOfStrings1.shouldNotContain(anotherKey)
        cacheOfStrings2.shouldNotContain(cacheKey)

        val cacheOfThrowables = redisCacheFactory.getInstance[String,Throwable](3.minutes.toMillis, getClass, "prefix10")

        Thread.sleep(500)

        cacheOfStrings1.shouldNotContain(cacheKey)
        cacheOfStrings2.shouldContain(anotherKey)
      }
    )
  }

  implicit class EnrichedCache[T](val cache: MonadCache[Future, String, T]) {
    def shouldContain(key: String): Unit = containsShouldBe(this.cache, key, expected = true)
    def shouldNotContain(key: String): Unit = containsShouldBe(this.cache, key, expected = false)

    def containsShouldBe[T](cache: MonadCache[Future, String, T], key: String, expected: Boolean): Unit = {
      Await.result(cache.contains(key), 1.second) should be(expected)
    }
  }

  override def afterAll() = {
    redisServer.stop
  }
}
