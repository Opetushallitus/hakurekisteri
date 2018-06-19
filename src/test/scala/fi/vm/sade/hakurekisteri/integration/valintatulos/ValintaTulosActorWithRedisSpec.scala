package fi.vm.sade.hakurekisteri.integration.valintatulos

import akka.actor.{ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import com.ning.http.client.AsyncHttpClient
import fi.vm.sade.hakurekisteri.MockConfig
import fi.vm.sade.hakurekisteri.integration._
import fi.vm.sade.hakurekisteri.integration.cache.CacheFactory
import fi.vm.sade.hakurekisteri.test.tools.FutureWaiting
import fi.vm.sade.scalaproperties.OphProperties
import fi.vm.sade.utils.tcp.PortChecker
import org.mockito.Mockito
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.mock.MockitoSugar
import org.scalatra.test.scalatest.ScalatraFunSuite
import redis.embedded.RedisServer

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

class ValintaTulosActorWithRedisSpec extends ScalatraFunSuite with FutureWaiting with DispatchSupport with MockitoSugar with ActorSystemSupport with LocalhostProperties with BeforeAndAfterAll {

  val rPort:Int = PortChecker.findFreeLocalPort
  val redisServer:RedisServer = new RedisServer(rPort)

  implicit val timeout: Timeout = 60.seconds
  val vtsConfig = ServiceConfig(serviceUrl = "http://localhost/valinta-tulos-service")
  val config = new MockConfig
  def cacheFactory(implicit system:ActorSystem) = CacheFactory.apply(new OphProperties()
    .addDefault("suoritusrekisteri.cache.redis.enabled", "true")
    .addDefault("suoritusrekisteri.cache.redis.host", "localhost")
    .addDefault("suoritusrekisteri.cache.redis.numberOfWaitersToLog", "5")
    .addDefault("suoritusrekisteri.cache.redis.cacheHandlingThreadPoolSize", "3")
    .addDefault("suoritusrekisteri.cache.redis.slowRedisRequestThresholdMillis", "0")
    .addDefault("suoritusrekisteri.cache.redis.port", s"${rPort}")
  )(system)

  def createEndPoint = {
    val e = mock[Endpoint]

    when(e.request(forUrl("http://localhost/valinta-tulos-service/haku/1.2.246.562.29.broken"))).thenReturn((500, List(), ""))
    when(e.request(forPattern("http://localhost/valinta-tulos-service/haku/1\\.2\\.246\\.562\\.29\\.[0-9]+"))).thenReturn((200, List(), ValintaTulosResults.haku))
    when(e.request(forUrl("http://localhost/valinta-tulos-service/haku/1.2.246.562.29.90697286251/hakemus/1.2.246.562.11.00000000576"))).thenReturn((200, List(), ValintaTulosResults.hakemus))

    e
  }

  override def beforeAll() = {
    redisServer.start
  }

  test("ValintaTulosActor should fire only one request to the backend even when asked multiple times") {
    withSystem(
      implicit system => {
        implicit val ec = system.dispatcher
        val endPoint = createEndPoint
        val valintaTulosActor = system.actorOf(Props(new ValintaTulosActor(config = config, cacheFactory = cacheFactory, client = new VirkailijaRestClient(config = vtsConfig, aClient = Some(new AsyncHttpClient(new CapturingProvider(endPoint)))))))

        valintaTulosActor ! UpdateValintatulos("1.2.246.562.29.90697286251")

        Thread.sleep(3000)

        valintaTulosActor ! ValintaTulosQuery("1.2.246.562.29.90697286251", None)
        valintaTulosActor ! ValintaTulosQuery("1.2.246.562.29.90697286251", None)
        valintaTulosActor ! ValintaTulosQuery("1.2.246.562.29.90697286251", None)
        valintaTulosActor ! ValintaTulosQuery("1.2.246.562.29.90697286251", None)

        waitFuture((valintaTulosActor ? ValintaTulosQuery("1.2.246.562.29.90697286251", None)).mapTo[SijoitteluTulos])(t => {
          t.valintatila("1.2.246.562.11.00000000576", "1.2.246.562.20.25463238029").get.toString should be (Valintatila.KESKEN.toString)
        })

        val cached = Await.result(cacheFactory.getInstance[String, SijoitteluTulos](1111,
          classOf[ValintaTulosActor], classOf[ValintaTulosActor], "sijoittelu-tulos").get("1.2.246.562.29.90697286251", (_: String) => Future.failed(new RuntimeException("should not be called"))), 10.seconds)
        cached.get.valintatila("1.2.246.562.11.00000000576", "1.2.246.562.20.25463238029").get.toString should be (Valintatila.KESKEN.toString)

        verify(endPoint, times(1)).request(forUrl("http://localhost/valinta-tulos-service/haku/1.2.246.562.29.90697286251"))
      }
    )
  }

  test("ValintaTulosActor should update cache periodically") {
    withSystem(
      implicit system => {
        implicit val ec = system.dispatcher
        val endPoint = createEndPoint
        val valintaTulosActor = system.actorOf(Props(new ValintaTulosActor(
          config = config,
          cacheFactory = cacheFactory,
          client = new VirkailijaRestClient(config = vtsConfig, aClient = Some(new AsyncHttpClient(new CapturingProvider(endPoint)))),
          refetchTime = Some(1000),
          cacheTime = Some(2000)
        )))

        valintaTulosActor ! UpdateValintatulos("1.2.246.562.29.90697286252")

        Thread.sleep(1500)

        verify(endPoint, atLeastOnce()).request(forUrl("http://localhost/valinta-tulos-service/haku/1.2.246.562.29.90697286252"))
      }
    )
  }

  test("ValintaTulosActor should use cached result also during refetch") {
    withSystem(
      implicit system => {
        implicit val ec = system.dispatcher
        val endPoint = createEndPoint
        val valintaTulosActor = system.actorOf(Props(new ValintaTulosActor(
          config = config,
          cacheFactory = cacheFactory,
          client = new VirkailijaRestClient(config = vtsConfig, aClient = Some(new AsyncHttpClient(new CapturingProvider(endPoint)))),
          refetchTime = Some(500),
          cacheTime = Some(1000)
        )))

        valintaTulosActor ! UpdateValintatulos("1.2.246.562.29.90697286253")

        Thread.sleep(550)

        valintaTulosActor ! ValintaTulosQuery("1.2.246.562.29.90697286253", None)
        valintaTulosActor ! ValintaTulosQuery("1.2.246.562.29.90697286253", None)
        valintaTulosActor ! ValintaTulosQuery("1.2.246.562.29.90697286253", None)
        valintaTulosActor ! ValintaTulosQuery("1.2.246.562.29.90697286253", None)

        waitFuture((valintaTulosActor ? ValintaTulosQuery("1.2.246.562.29.90697286253", None)).mapTo[SijoitteluTulos])(t => {
          t.valintatila("1.2.246.562.11.00000000576", "1.2.246.562.20.25463238029").get.toString should be (Valintatila.KESKEN.toString)
        })

        val cached = Await.result(cacheFactory.getInstance[String, SijoitteluTulos](1111,
          classOf[ValintaTulosActor], classOf[ValintaTulosActor], "sijoittelu-tulos").get("1.2.246.562.29.90697286253", (_: String) => Future.failed(new RuntimeException("should not be called"))), 10.seconds)
        cached.get.valintatila("1.2.246.562.11.00000000576", "1.2.246.562.20.25463238029").get.toString should be (Valintatila.KESKEN.toString)

        verify(endPoint, times(1)).request(forUrl("http://localhost/valinta-tulos-service/haku/1.2.246.562.29.90697286253"))
      }
    )
  }

  test("ValintaTulosActor should refetch if request fails") {
    withSystem(
      implicit system => {
        implicit val ec = system.dispatcher
        val endPoint = createEndPoint
        val valintaTulosActor = system.actorOf(Props(new ValintaTulosActor(
          config = config,
          cacheFactory = cacheFactory,
          client = new VirkailijaRestClient(config = vtsConfig, aClient = Some(new AsyncHttpClient(new CapturingProvider(endPoint)))),
          refetchTime = Some(500),
          cacheTime = Some(1000),
          retryTime = Some(100)
        )))

        valintaTulosActor ! UpdateValintatulos("1.2.246.562.29.broken")

        Thread.sleep(200)

        verify(endPoint, Mockito.atLeast(2)).request(forUrl("http://localhost/valinta-tulos-service/haku/1.2.246.562.29.broken"))
      }
    )
  }

  test("ValintaTulosActor should block queries if initial loading is still on-going") {
    withSystem(
      implicit system => {
        implicit val ec = system.dispatcher
        val endPoint = createEndPoint
        val valintaTulosActor = system.actorOf(Props(new ValintaTulosActor(
          config = config,
          cacheFactory = cacheFactory,
          client = new VirkailijaRestClient(config = vtsConfig, aClient = Some(new AsyncHttpClient(new CapturingProvider(endPoint)))),
          refetchTime = Some(500),
          cacheTime = Some(1000),
          retryTime = Some(100)
        )))

        valintaTulosActor ! BatchUpdateValintatulos((1 to 10).map(i => UpdateValintatulos(s"1.2.246.562.29.$i")).toSet)

        expectFailure[InitialLoadingNotDone]((valintaTulosActor ? ValintaTulosQuery("1.2.246.562.29.1", None)).mapTo[SijoitteluTulos])
      }
    )
  }

  test("ValintaTulosActor should skip initial loading if data is already in redis") {
    withSystem(
      implicit system => {
        implicit val ec = system.dispatcher
        val endPoint = createEndPoint
        val valintaTulosActor = system.actorOf(Props(new ValintaTulosActor(
          config = config,
          cacheFactory = cacheFactory,
          client = new VirkailijaRestClient(config = vtsConfig, aClient = Some(new AsyncHttpClient(new CapturingProvider(endPoint)))),
          refetchTime = Some(500),
          cacheTime = Some(1000),
          retryTime = Some(100)
        )))

        valintaTulosActor ! BatchUpdateValintatulos((11 to 12).map(i => UpdateValintatulos(s"1.2.246.562.29.$i")).toSet)

        Thread.sleep(300)

        val cache = cacheFactory.getInstance[String, SijoitteluTulos](1111, classOf[ValintaTulosActor], classOf[ValintaTulosActor], "sijoittelu-tulos")
        Await.result(cache.contains("1.2.246.562.29.11"), 1.second) should be(true)
        Await.result(cache.contains("1.2.246.562.29.12"), 1.second) should be(true)
        Await.result(cache.contains("1.2.246.562.29.13"), 1.second) should be(false)
        Await.result(cache.contains("1.2.246.562.29.14"), 1.second) should be(false)

        verify(endPoint).request(forUrl("http://localhost/valinta-tulos-service/haku/1.2.246.562.29.11"))
        verify(endPoint).request(forUrl("http://localhost/valinta-tulos-service/haku/1.2.246.562.29.12"))
      }
    )
    withSystem(
      implicit system => {
        implicit val ec = system.dispatcher
        val endPoint = createEndPoint
        val valintaTulosActor = system.actorOf(Props(new ValintaTulosActor(
          config = config,
          cacheFactory = cacheFactory,
          client = new VirkailijaRestClient(config = vtsConfig, aClient = Some(new AsyncHttpClient(new CapturingProvider(endPoint)))),
          refetchTime = Some(500),
          cacheTime = Some(1000),
          retryTime = Some(100)
        )))

        valintaTulosActor ! BatchUpdateValintatulos((11 to 14).map(i => UpdateValintatulos(s"1.2.246.562.29.$i")).toSet)

        Thread.sleep(300)

        val cache = cacheFactory.getInstance[String, SijoitteluTulos](1111, classOf[ValintaTulosActor], classOf[ValintaTulosActor], "sijoittelu-tulos")
        Await.result(cache.contains("1.2.246.562.29.11"), 1.second) should be(true)
        Await.result(cache.contains("1.2.246.562.29.12"), 1.second) should be(true)
        Await.result(cache.contains("1.2.246.562.29.13"), 1.second) should be(true)
        Await.result(cache.contains("1.2.246.562.29.14"), 1.second) should be(true)

        verify(endPoint, never()).request(forUrl("http://localhost/valinta-tulos-service/haku/1.2.246.562.29.11"))
        verify(endPoint, never()).request(forUrl("http://localhost/valinta-tulos-service/haku/1.2.246.562.29.12"))
        verify(endPoint).request(forUrl("http://localhost/valinta-tulos-service/haku/1.2.246.562.29.13"))
        verify(endPoint).request(forUrl("http://localhost/valinta-tulos-service/haku/1.2.246.562.29.14"))
      }
    )
  }

  override def afterAll() = {
    redisServer.stop
  }
}
