package fi.vm.sade.hakurekisteri.integration

import akka.actor.ActorSystem
import fi.vm.sade.hakurekisteri.integration.cache.CacheFactory.RedisCacheInitializationException
import fi.vm.sade.hakurekisteri.integration.cache.{CacheFactory, MonadCache}
import fi.vm.sade.hakurekisteri.integration.koodisto.GetRinnasteinenKoodiArvoQuery
import fi.vm.sade.hakurekisteri.integration.tarjonta.Hakukohde
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

  val port: Int = PortChecker.findFreeLocalPort
  val redisServer = new RedisServer(port)

  def redisCacheFactory(implicit system:ActorSystem): CacheFactory = CacheFactory.apply(new OphProperties()
    .addDefault("suoritusrekisteri.cache.redis.enabled", "true")
    .addDefault("suoritusrekisteri.cache.redis.host", "localhost")
    .addDefault("suoritusrekisteri.cache.redis.numberOfWaitersToLog", "5")
    .addDefault("suoritusrekisteri.cache.redis.cacheHandlingThreadPoolSize", "3")
    .addDefault("suoritusrekisteri.cache.redis.slowRedisRequestThresholdMillis", "0")
    .addDefault("suoritusrekisteri.cache.redis.port", s"$port"))(system)

  override def beforeAll(): Unit = {
    redisServer.start()
  }

  val cacheKey = "foo"
  val cacheEntry = "bar"
  val cacheEntryF: Future[String] = Future.successful(cacheEntry)

  val concurrencyTestLoopCount: Int = 5
  val concurrencyTestParallelRequestCount: Int = 10
  val concurrencyTestResultsWaitDuration: Duration = 10.seconds
  private val stringLoader: String => Future[Nothing] = (_: String) => Future.failed(new RuntimeException("should not be called"))

  private val javaStringSerialVersionUID: Long = -6849794470754667710l
  private val throwableSerialVersionUID: Long = -3042686055658047285l

  it should "add an entry to cache" in {
   withSystem(
      implicit system => {
        val cache = redisCacheFactory.getInstance[String,String](3.minutes.toMillis, this.getClass, classOf[String], "prefix1")

        Await.result(cache + (cacheKey, cacheEntry), 5.seconds)

        cache.shouldContain(cacheKey)

        Await.result(cache.get(cacheKey, stringLoader), 10.seconds) should be (Some(cacheEntry))
      }
    )
  }

  it should "remove an entry from cache" in {
    withSystem(
      implicit system => {
        val cache = redisCacheFactory.getInstance[String, String](3.minutes.toMillis, this.getClass, classOf[String], "prefix2")

        Await.result(cache + (cacheKey, cacheEntry), 5.seconds)

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
        val cache = redisCacheFactory.getInstance[String,String](3.minutes.toMillis, this.getClass, classOf[String], "prefix3")

        Await.result(cache + (cacheKey, cacheEntry), 5.seconds)
      }
    )
    withSystem(
      implicit system => {
        val cache = redisCacheFactory.getInstance[String,String](3.minutes.toMillis, this.getClass, classOf[String], "prefix3")

        cache.shouldContain(cacheKey)
      }
    )
  }

  it should "use prefixes" in {
    withSystem(
      implicit system => {
        val cache = redisCacheFactory.getInstance[String,String](3.minutes.toMillis, this.getClass, classOf[String], "prefix4")

        Await.result(cache + (cacheKey, cacheEntry), 5.seconds)
      }
    )
    withSystem(
      implicit system => {
        val cache4 = redisCacheFactory.getInstance[String,String](3.minutes.toMillis, this.getClass, classOf[String], "prefix4")
        val cache5 =  redisCacheFactory.getInstance[String,String](3.minutes.toMillis, this.getClass, classOf[String], "prefix5")

        cache5.shouldNotContain(cacheKey)
        cache4.shouldContain(cacheKey)
      }
    )
  }

  it should "only do one backend call per missing value with loader accepting get API" in {
    1.to(concurrencyTestLoopCount).foreach { counter =>
      withSystem(
         implicit system => {
           val cache = redisCacheFactory.getInstance[String,String](3.minutes.toMillis, this.getClass, classOf[String], s"prefixLoaderApi$counter")

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
        val cache = redisCacheFactory.getInstance[String,String](3.minutes.toMillis, this.getClass, classOf[String], "prefix6")

        Await.result(cache + (cacheKey, cacheEntry), 5.seconds)

        cache.shouldContain("version")
        Await.result(cache.getVersion, 5.seconds) should be(Some(javaStringSerialVersionUID))
      }
    )
  }

  it should "update version in cache when it changes" in {
    withSystem(
      implicit system => {
        val cacheOfStrings = redisCacheFactory.getInstance[String,String](3.minutes.toMillis, this.getClass, classOf[String], "prefix7")

        Await.result(cacheOfStrings + (cacheKey, cacheEntry), 5.seconds)

        cacheOfStrings.shouldContain("version")

        Await.result(cacheOfStrings.getVersion, 5.seconds) shouldBe Some(javaStringSerialVersionUID)

        val cacheOfThrowables = redisCacheFactory.getInstance[String,Throwable](3.minutes.toMillis, this.getClass, classOf[Throwable], "prefix7")

        Thread.sleep(500)

        Await.result(cacheOfStrings.getVersion, 5.seconds) should be(Some(throwableSerialVersionUID))
        Await.result(cacheOfThrowables.getVersion, 5.seconds) should be(Some(throwableSerialVersionUID))
      }
    )
  }

  it should "clear cache when the version changes" in {
    withSystem(
      implicit system => {
        val cacheOfStrings = redisCacheFactory.getInstance[String,String](3.minutes.toMillis, this.getClass, classOf[String], "prefix8")

        Await.result(cacheOfStrings + (cacheKey, cacheEntry), 5.seconds)

        cacheOfStrings.shouldContain(cacheKey)

        val cacheOfThrowables = redisCacheFactory.getInstance[String,Throwable](3.minutes.toMillis, this.getClass, classOf[Throwable], "prefix8")

        Thread.sleep(500)

        cacheOfStrings.shouldNotContain(cacheKey)
        cacheOfThrowables.shouldNotContain(cacheKey)
      }
    )
  }

  it should "not clear cache when creating a new instance with same version" in {
    withSystem(
      implicit system => {
        val cache1 = redisCacheFactory.getInstance[String,String](3.minutes.toMillis, this.getClass, classOf[String], "prefix9")

        Await.result(cache1 + (cacheKey, cacheEntry), 5.seconds)

        cache1.shouldContain(cacheKey)

        val cache2 = redisCacheFactory.getInstance[String,String](3.minutes.toMillis, this.getClass, classOf[String], "prefix9")

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
        val cacheOfStrings1  = redisCacheFactory.getInstance[String,String](3.minutes.toMillis, this.getClass, classOf[String], "prefix10")
        val cacheOfStrings2 = redisCacheFactory.getInstance[String,String](3.minutes.toMillis, this.getClass, classOf[String], "prefix11")

        Await.result(cacheOfStrings1 + (cacheKey, cacheEntry), 5.seconds)
        val anotherKey = "anotherkey"
        Await.result(cacheOfStrings2 + (anotherKey, cacheEntry), 5.seconds)

        cacheOfStrings1.shouldContain(cacheKey)
        cacheOfStrings2.shouldContain(anotherKey)
        cacheOfStrings1.shouldNotContain(anotherKey)
        cacheOfStrings2.shouldNotContain(cacheKey)

        val cacheOfThrowables = redisCacheFactory.getInstance[String,Throwable](3.minutes.toMillis, this.getClass, classOf[Throwable], "prefix10")

        Thread.sleep(500)

        cacheOfStrings1.shouldNotContain(cacheKey)
        cacheOfStrings2.shouldContain(anotherKey)
      }
    )
  }

  it should "resolve the correct version for a case class option" in {
    withSystem(
      implicit system => {
        val expectedVersion: Long = java.io.ObjectStreamClass.lookup(manifest[Hakukohde].runtimeClass).getSerialVersionUID

        val cacheOfHakukohdes = redisCacheFactory.getInstance[String, Option[Hakukohde]](3.minutes.toMillis, this.getClass, classOf[Hakukohde], "prefix12")

        Await.result(cacheOfHakukohdes.getVersion, 1.minute).get shouldBe expectedVersion
      }
    )
  }

  it should "resolve different versions for options of different case classes" in {
    withSystem(
      implicit system => {
        manifest[Option[Hakukohde]] shouldNot be(manifest[Option[GetRinnasteinenKoodiArvoQuery]])

        val cacheOfHakukohdes = redisCacheFactory.getInstance[String, Option[Hakukohde]](3.minutes.toMillis, this.getClass, classOf[Hakukohde], "prefix13")
        val hakukohdeVersion = Await.result(cacheOfHakukohdes.getVersion, 1.minute).get

        val cacheOfKoodis = redisCacheFactory.getInstance[String, Option[GetRinnasteinenKoodiArvoQuery]](3.minutes.toMillis, this.getClass, classOf[GetRinnasteinenKoodiArvoQuery], "prefix14")
        val koodiVersion = Await.result(cacheOfKoodis.getVersion, 1.minute).get

        koodiVersion shouldNot be(hakukohdeVersion)
      }
    )
  }

  it should "throw an exception if initializing cache with non-serializable classOfT" in {
    withSystem(
      implicit system => {
        intercept[RedisCacheInitializationException] {
          redisCacheFactory.getInstance[String, String](3.minutes.toMillis, this.getClass, classOf[ActorSystem], "prefix15")
        }
      }
    )
  }

  implicit class EnrichedCache[T](val cache: MonadCache[Future, String, T]) {
    def shouldContain(key: String): Unit = containsShouldBe(this.cache, key, expected = true)
    def shouldNotContain(key: String): Unit = containsShouldBe(this.cache, key, expected = false)

    def containsShouldBe(cache: MonadCache[Future, String, T], key: String, expected: Boolean): Unit = {
      Await.result(cache.contains(key), 5.seconds) should be(expected)
    }
  }

  override def afterAll(): Unit = {
    redisServer.stop()
  }
}
