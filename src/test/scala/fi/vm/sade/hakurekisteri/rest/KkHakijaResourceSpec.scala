package fi.vm.sade.hakurekisteri.rest

import akka.actor.{ActorRef, Props}
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.{Config, MockCacheFactory}
import fi.vm.sade.hakurekisteri.acceptance.tools.HakeneetSupport
import fi.vm.sade.hakurekisteri.integration._
import fi.vm.sade.hakurekisteri.integration.hakemus._
import fi.vm.sade.hakurekisteri.integration.hakukohde.HakukohdeAggregatorActorRef
import fi.vm.sade.hakurekisteri.integration.hakukohderyhma.HakukohderyhmaService
import fi.vm.sade.hakurekisteri.integration.henkilo.MockOppijaNumeroRekisteri
import fi.vm.sade.hakurekisteri.integration.koodisto.KoodistoActorRef
import fi.vm.sade.hakurekisteri.integration.kouta.KoutaInternalActorRef
import fi.vm.sade.hakurekisteri.integration.organisaatio.OrganisaatioActorRef
import fi.vm.sade.hakurekisteri.integration.tarjonta.TarjontaActorRef
import fi.vm.sade.hakurekisteri.integration.valintaperusteet.ValintaperusteetServiceMock
import fi.vm.sade.hakurekisteri.integration.valintarekisteri.ValintarekisteriActorRef
import fi.vm.sade.hakurekisteri.integration.valintatulos.ValintaTulosActorRef
import fi.vm.sade.hakurekisteri.web.kkhakija.{KkHakijaResource, KkHakijaService}
import fi.vm.sade.hakurekisteri.web.rest.support.{HakurekisteriSwagger, TestSecurity}
import org.mockito.Mockito._
import org.scalatest.concurrent.Waiters
import org.scalatest.mockito.MockitoSugar
import org.scalatra.swagger.Swagger
import org.scalatra.test.scalatest.ScalatraFunSuite

import scala.concurrent.Await
import scala.concurrent.duration._

class KkHakijaResourceSpec
    extends ScalatraFunSuite
    with HakeneetSupport
    with MockitoSugar
    with DispatchSupport
    with Waiters
    with LocalhostProperties {
  private implicit val swagger: Swagger = new HakurekisteriSwagger
  private implicit val security: TestSecurity = new TestSecurity

  private val endPoint = mock[Endpoint]
  private val hakuappClient = new VirkailijaRestClient(
    ServiceConfig(serviceUrl = "http://localhost/haku-app"),
    aClient = Some(new CapturingAsyncHttpClient(endPoint))
  )
  private val ataruClient = new VirkailijaRestClient(
    ServiceConfig(serviceUrl = "http://localhost/lomake-editori"),
    aClient = Some(new CapturingAsyncHttpClient(endPoint))
  )
  private val organisaatioMock: OrganisaatioActorRef = new OrganisaatioActorRef(
    system.actorOf(Props(new MockedOrganisaatioActor()))
  )
  private val hakukohdeAggregatorMock = new HakukohdeAggregatorActorRef(mock[ActorRef])
  private val koutaInternalActorMock = new KoutaInternalActorRef(mock[ActorRef])
  private val hakemusService = new HakemusService(
    hakuappClient,
    ataruClient,
    hakukohdeAggregatorMock,
    koutaInternalActorMock,
    organisaatioMock,
    MockOppijaNumeroRekisteri,
    Config.mockDevConfig,
    MockCacheFactory.get()
  )
  private val hakuMock = mock[ActorRef]
  private val suoritusMock = mock[ActorRef]
  private val valintaTulosMock = new ValintaTulosActorRef(mock[ActorRef])
  private val valintaRekisteri = new ValintarekisteriActorRef(mock[ActorRef])
  private val koodistoMock = new KoodistoActorRef(mock[ActorRef])
  private val valintaperusteetMock = new ValintaperusteetServiceMock

  val service = new KkHakijaService(
    hakemusService,
    mock[Hakupalvelu],
    mock[HakukohderyhmaService],
    hakukohdeAggregatorMock,
    hakuMock,
    koodistoMock,
    suoritusMock,
    valintaTulosMock,
    valintaRekisteri,
    valintaperusteetMock,
    Timeout(1.minute)
  )
  val resource = new KkHakijaResource(service)
  addServlet(resource, "/")

  override def beforeEach() {
    super.beforeEach()
    reset(endPoint)
  }

  test("should return 200 OK") {
    when(endPoint.request(forPattern(".*listfull.*"))).thenReturn((200, List(), "[]"))
    when(endPoint.request(forPattern(".*/lomake-editori/api/external/suoritusrekisteri")))
      .thenReturn((200, List(), "{\"applications\": []}"))
    Thread.sleep(2000)

    get("/?hakukohde=1.11.1") {
      status should be(200)
    }
  }

  test("should return 400 Bad Request if no parameters given") {
    get("/") {
      status should be(400)
    }
  }

  override def stop(): Unit = {
    Await.result(system.terminate(), 15.seconds)
    super.stop()
  }
}
