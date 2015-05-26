package fi.vm.sade.hakurekisteri.integration.organisaatio

import akka.actor.{Props, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import com.ning.http.client.AsyncHttpClient
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration._
import org.mockito.Mockito._
import org.scalatest.Matchers
import org.scalatest.concurrent.AsyncAssertions
import org.scalatest.mock.MockitoSugar
import org.scalatest.time.{Millis, Span}
import org.scalatra.test.scalatest.ScalatraFunSuite
import scala.concurrent.Future
import scala.concurrent.duration._

class OrganisaatioActorSpec extends ScalatraFunSuite with Matchers with AsyncAssertions with MockitoSugar with DispatchSupport {

  implicit val system = ActorSystem("organisaatioactor-test-system")
  implicit val ec = system.dispatcher
  implicit val timeout: Timeout = 60.seconds

  val endPoint = mock[Endpoint]

  when(endPoint.request(forUrl("http://localhost/organisaatio-service/rest/organisaatio/v2/hierarkia/hae?aktiiviset=true&lakkautetut=false&suunnitellut=true"))).thenReturn((200, List(), OrganisaatioResults.all))
  when(endPoint.request(forUrl("http://localhost/organisaatio-service/rest/organisaatio/00004"))).thenReturn((200, List(), OrganisaatioResults.alppila))
  when(endPoint.request(forUrl("http://localhost/organisaatio-service/rest/organisaatio/05127"))).thenReturn((200, List(), OrganisaatioResults.pikkola))
  when(endPoint.request(forUrl("http://localhost/organisaatio-service/rest/organisaatio/1.2.246.562.10.16546622305"))).thenReturn((200, List(), OrganisaatioResults.pikkola))

  val organisaatioConfig = ServiceConfig(serviceUrl = "http://localhost/organisaatio-service")

  val organisaatioActor = system.actorOf(Props(new HttpOrganisaatioActor(new VirkailijaRestClient(config = organisaatioConfig, aClient = Some(new AsyncHttpClient(new CapturingProvider(endPoint)))), Config.mockConfig)))

  test("OrganisaatioActor should contain all organisaatios after startup") {
    Thread.sleep(1000)

    waitFuture((organisaatioActor ? Oppilaitos("05127")).mapTo[OppilaitosResponse])(o => {
      o.oppilaitos.oppilaitosKoodi.get should be ("05127")
    })

    verify(endPoint, times(1)).request(forUrl("http://localhost/organisaatio-service/rest/organisaatio/v2/hierarkia/hae?aktiiviset=true&lakkautetut=false&suunnitellut=true"))
    verify(endPoint, times(0)).request(forUrl("http://localhost/organisaatio-service/rest/organisaatio/05127"))
  }

  test("OrganisaatioActor should return organisaatio from cache using organisaatio oid") {
    Thread.sleep(1000)

    waitFuture((organisaatioActor ? "1.2.246.562.10.16546622305").mapTo[Option[Organisaatio]])(o => {
      o.get.oppilaitosKoodi.get should be ("05127")
    })

    verify(endPoint, times(1)).request(forUrl("http://localhost/organisaatio-service/rest/organisaatio/v2/hierarkia/hae?aktiiviset=true&lakkautetut=false&suunnitellut=true"))
    verify(endPoint, times(0)).request(forUrl("http://localhost/organisaatio-service/rest/organisaatio/1.2.246.562.10.16546622305"))
  }

  test("OrganisaatioActor should return organisaatio from cache using oppilaitoskoodi") {
    Thread.sleep(1000)

    waitFuture((organisaatioActor ? Oppilaitos("05127")).mapTo[OppilaitosResponse])(o => {
      o.oppilaitos.oppilaitosKoodi.get should be ("05127")
    })

    verify(endPoint, times(1)).request(forUrl("http://localhost/organisaatio-service/rest/organisaatio/v2/hierarkia/hae?aktiiviset=true&lakkautetut=false&suunnitellut=true"))
    verify(endPoint, times(0)).request(forUrl("http://localhost/organisaatio-service/rest/organisaatio/05127"))
  }

  test("OrganisaatioActor should find organisaatio from organisaatio-service if not found in cache") {
    Thread.sleep(1000)

    waitFuture((organisaatioActor ? Oppilaitos("00004")).mapTo[OppilaitosResponse])(o => {
      o.oppilaitos.oppilaitosKoodi.get should be ("00004")
    })

    verify(endPoint, times(1)).request(forUrl("http://localhost/organisaatio-service/rest/organisaatio/v2/hierarkia/hae?aktiiviset=true&lakkautetut=false&suunnitellut=true"))
    verify(endPoint, times(1)).request(forUrl("http://localhost/organisaatio-service/rest/organisaatio/00004"))
  }

  test("OrganisaatioActor should cache a single result") {
    Thread.sleep(1000)

    waitFuture((organisaatioActor ? Oppilaitos("00004")).mapTo[OppilaitosResponse])(o => {
      o.oppilaitos.oppilaitosKoodi.get should be ("00004")
    })

    Thread.sleep(1000)

    waitFuture((organisaatioActor ? Oppilaitos("00004")).mapTo[OppilaitosResponse])(o => {
      o.oppilaitos.oppilaitosKoodi.get should be ("00004")
    })

    verify(endPoint, times(1)).request(forUrl("http://localhost/organisaatio-service/rest/organisaatio/v2/hierarkia/hae?aktiiviset=true&lakkautetut=false&suunnitellut=true"))
    verify(endPoint, times(1)).request(forUrl("http://localhost/organisaatio-service/rest/organisaatio/00004"))
  }

  override def stop(): Unit = {
    system.shutdown()
    system.awaitTermination(15.seconds)
    super.stop()
  }

  def waitFuture[A](f: Future[A])(assertion: A => Unit) = {
    val w = new Waiter

    f.onComplete(r => {
      w(assertion(r.get))
      w.dismiss()
    })

    w.await(timeout(Span(5000, Millis)), dismissals(1))
  }

}

object OrganisaatioResults {
  val all = scala.io.Source.fromURL(getClass.getResource("/mock-data/organisaatio/organisaatio-all.json")).mkString
  val pikkola = scala.io.Source.fromURL(getClass.getResource("/mock-data/organisaatio/organisaatio-pikkola.json")).mkString
  val alppila = scala.io.Source.fromURL(getClass.getResource("/mock-data/organisaatio/organisaatio-alppilanlukio.json")).mkString
}