package fi.vm.sade.hakurekisteri.integration.valintatulos

import java.util.concurrent.TimeUnit

import akka.actor.Props
import akka.pattern.ask
import akka.util.Timeout
import com.ning.http.client.AsyncHttpClient
import fi.vm.sade.hakurekisteri.{ MockCacheFactory, MockConfig}
import fi.vm.sade.hakurekisteri.integration._
import fi.vm.sade.hakurekisteri.test.tools.FutureWaiting
import org.mockito.Mockito._
import org.mockito.Mockito
import org.scalatest.mock.MockitoSugar
import org.scalatra.test.scalatest.ScalatraFunSuite

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

class ValintaTulosActorSpec extends ScalatraFunSuite with FutureWaiting with DispatchSupport with MockitoSugar with ActorSystemSupport with LocalhostProperties {

  implicit val timeout: Timeout = 60.seconds
  val vtsConfig = ServiceConfig(serviceUrl = "http://localhost/valinta-tulos-service")
  val config = new MockConfig
  val cacheFactory = MockCacheFactory.get

  def createEndPoint = {
    val e = mock[Endpoint]

    when(e.request(forUrl("http://localhost/valinta-tulos-service/haku/1.2.246.562.29.broken"))).thenReturn((500, List(), ""))
    when(e.request(forPattern("http://localhost/valinta-tulos-service/haku/1\\.2\\.246\\.562\\.29\\.[0-9]+"))).thenReturn((200, List(), ValintaTulosResults.haku))
    when(e.request(forUrl("http://localhost/valinta-tulos-service/haku/1.2.246.562.29.90697286251/hakemus/1.2.246.562.11.00000000576"))).thenReturn((200, List(), ValintaTulosResults.hakemus))

    e
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

        verify(endPoint).request(forUrl("http://localhost/valinta-tulos-service/haku/1.2.246.562.29.90697286251"))
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

        valintaTulosActor ! UpdateValintatulos("1.2.246.562.29.90697286251")

        Thread.sleep(1500)

        verify(endPoint, atLeastOnce()).request(forUrl("http://localhost/valinta-tulos-service/haku/1.2.246.562.29.90697286251"))
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

        valintaTulosActor ! UpdateValintatulos("1.2.246.562.29.90697286251")

        Thread.sleep(550)

        valintaTulosActor ! ValintaTulosQuery("1.2.246.562.29.90697286251", None)
        valintaTulosActor ! ValintaTulosQuery("1.2.246.562.29.90697286251", None)
        valintaTulosActor ! ValintaTulosQuery("1.2.246.562.29.90697286251", None)
        valintaTulosActor ! ValintaTulosQuery("1.2.246.562.29.90697286251", None)

        waitFuture((valintaTulosActor ? ValintaTulosQuery("1.2.246.562.29.90697286251", None)).mapTo[SijoitteluTulos])(t => {
          t.valintatila("1.2.246.562.11.00000000576", "1.2.246.562.20.25463238029").get.toString should be (Valintatila.KESKEN.toString)
        })

        verify(endPoint).request(forUrl("http://localhost/valinta-tulos-service/haku/1.2.246.562.29.90697286251"))
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

}

object ValintaTulosResults {
  def haku(implicit ec: ExecutionContext) =  {
    Await.result(Future { Thread.sleep(20) }, Duration(1, TimeUnit.SECONDS))
    scala.io.Source.fromURL(getClass.getResource("/mock-data/valintatulos/valintatulos-haku.json")).mkString
  }
  val hakemus = scala.io.Source.fromURL(getClass.getResource("/mock-data/valintatulos/valintatulos-hakemus.json")).mkString
}

