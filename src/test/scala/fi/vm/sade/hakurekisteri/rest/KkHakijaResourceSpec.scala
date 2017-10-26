package fi.vm.sade.hakurekisteri.rest

import akka.actor.ActorRef
import com.ning.http.client.AsyncHttpClient
import fi.vm.sade.hakurekisteri.acceptance.tools.HakeneetSupport
import fi.vm.sade.hakurekisteri.integration._
import fi.vm.sade.hakurekisteri.integration.hakemus._
import fi.vm.sade.hakurekisteri.integration.henkilo.MockOppijaNumeroRekisteri
import fi.vm.sade.hakurekisteri.web.kkhakija.{KkHakijaResource, KkHakijaService}
import fi.vm.sade.hakurekisteri.web.rest.support.{HakurekisteriSwagger, TestSecurity}
import org.mockito.Mockito._
import org.scalatest.concurrent.AsyncAssertions
import org.scalatest.mock.MockitoSugar
import org.scalatra.swagger.Swagger
import org.scalatra.test.scalatest.ScalatraFunSuite

import scala.concurrent.Await
import scala.concurrent.duration._

class KkHakijaResourceSpec extends ScalatraFunSuite with HakeneetSupport with MockitoSugar with DispatchSupport with AsyncAssertions with LocalhostProperties {
  private implicit val swagger: Swagger = new HakurekisteriSwagger
  private implicit val security: TestSecurity = new TestSecurity

  private val endPoint = mock[Endpoint]
  private val asyncProvider = new CapturingProvider(endPoint)
  private val hakuappClient = new VirkailijaRestClient(ServiceConfig(serviceUrl = "http://localhost/haku-app"), aClient = Some(new AsyncHttpClient(asyncProvider)))
  private val ataruClient = new VirkailijaRestClient(ServiceConfig(serviceUrl = "http://localhost/lomake-editori"), aClient = Some(new AsyncHttpClient(asyncProvider)))
  private val hakemusService = new HakemusService(hakuappClient, ataruClient, MockOppijaNumeroRekisteri)
  private val tarjontaMock = mock[ActorRef]
  private val hakuMock = mock[ActorRef]
  private val suoritusMock = mock[ActorRef]
  private val valintaTulosMock = mock[ActorRef]
  private val valintaRekisteri = mock[ActorRef]
  private val koodistoMock = mock[ActorRef]

  val service = new KkHakijaService(hakemusService, mock[Hakupalvelu], tarjontaMock, hakuMock, koodistoMock, suoritusMock, valintaTulosMock, valintaRekisteri)
  val resource = new KkHakijaResource(service)
  addServlet(resource, "/")

  override def beforeEach() {
    super.beforeEach()
    reset(endPoint)
  }

  test("should return 200 OK") {
    when(endPoint.request(forPattern(".*listfull.*"))).thenReturn((200, List(), "[]"))
    when(endPoint.request(forPattern(".*/lomake-editori/api/external/hakurekisteri/applications.*")))
      .thenReturn((200, List(), "[]"))
    Thread.sleep(2000)

    get("/?hakukohde=1.11.1") {
      status should be (200)
    }
  }

  test("should return 400 Bad Request if no parameters given") {
    get("/") {
      status should be (400)
    }
  }

  override def stop(): Unit = {
    Await.result(system.terminate(), 15.seconds)
    super.stop()
  }
}
