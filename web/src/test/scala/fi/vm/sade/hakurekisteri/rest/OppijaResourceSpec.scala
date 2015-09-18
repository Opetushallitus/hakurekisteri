package fi.vm.sade.hakurekisteri.rest

import java.util.UUID

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.testkit.TestActorRef
import com.ning.http.client.AsyncHttpClient
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.acceptance.tools.FakeAuthorizer
import fi.vm.sade.hakurekisteri.arvosana.ArvosanaActor
import fi.vm.sade.hakurekisteri.batchimport.ImportBatch
import fi.vm.sade.hakurekisteri.ensikertalainen.EnsikertalainenActor
import fi.vm.sade.hakurekisteri.integration._
import fi.vm.sade.hakurekisteri.integration.hakemus._
import fi.vm.sade.hakurekisteri.integration.tarjonta.{GetKomoQuery, Komo, KomoResponse, Koulutuskoodi}
import fi.vm.sade.hakurekisteri.integration.valintarekisteri.ValintarekisteriActor
import fi.vm.sade.hakurekisteri.opiskelija.OpiskelijaActor
import fi.vm.sade.hakurekisteri.opiskeluoikeus.OpiskeluoikeusActor
import fi.vm.sade.hakurekisteri.rest.support.{Registers, User}
import fi.vm.sade.hakurekisteri.storage.repository.{InMemJournal, Updated}
import fi.vm.sade.hakurekisteri.suoritus.{SuoritusActor, VirallinenSuoritus, yksilollistaminen}
import fi.vm.sade.hakurekisteri.test.tools.{FutureWaiting, MockedResourceActor}
import fi.vm.sade.hakurekisteri.web.oppija.OppijaResource
import fi.vm.sade.hakurekisteri.web.rest.support.{HakurekisteriSwagger, TestSecurity}
import org.joda.time.{DateTime, LocalDate}
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatra.swagger.Swagger
import org.scalatra.test.scalatest.ScalatraFunSuite

import scala.compat.Platform
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.implicitConversions

class OppijaResourceSpec extends OppijaResourceSetup {
  
  test("OppijaResource should return 200") {
    get("/?haku=1") {
      response.status should be (200)
    }
  }

  test("OppijaResource should return 400 if no parameters are given") {
    get("/") {
      response.status should be (400)
    }
  }

  test("OppijaResource should return 10001 oppijas with ensikertalainen false") {
    waitFuture(resource.fetchOppijat(HakemusQuery(Some("foo"), None, None)))(oppijat => {
      oppijat.length should be (10001)
      oppijat.foreach(o => o.ensikertalainen should be (Some(false)))
    })
  }

  test("OppijaResource should not cache ensikertalaisuus") {
    valintarekisteri.underlyingActor.requestCount = 0
    get("/?haku=foo") {
      get("/?haku=foo") {
        valintarekisteri.underlyingActor.requestCount should be (20002)
      }
    }
  }

}

abstract class OppijaResourceSetup extends ScalatraFunSuite with MockitoSugar with DispatchSupport with FutureWaiting {
  implicit val system = ActorSystem("oppija-resource-test-system")
  implicit val security = new TestSecurity
  implicit val user: User = security.TestUser
  implicit val swagger: Swagger = new HakurekisteriSwagger

  val henkilot: Set[String] = (0 until 10001).map(i => UUID.randomUUID().toString).toSet

  val suorituksetSeq = henkilot.map(henkilo =>
    VirallinenSuoritus("bar", "foo", "VALMIS", new LocalDate(2001, 1, 1), henkilo, yksilollistaminen.Ei, "FI", None, vahv = true, "")
  ).toSeq

  implicit def seq2journal[R <: fi.vm.sade.hakurekisteri.rest.support.Resource[UUID, R]](s:Seq[R]): InMemJournal[R, UUID] = {
    val journal = new InMemJournal[R, UUID]
    s.foreach((resource:R) => journal.addModification(Updated(resource.identify(UUID.randomUUID()))))
    journal
  }
  implicit def seq2journalString[R <: fi.vm.sade.hakurekisteri.rest.support.Resource[String, R]](s:Seq[R]): InMemJournal[R, String] = {
    val journal = new InMemJournal[R, String]
    s.foreach((resource:R) => journal.addModification(Updated(resource.identify(UUID.randomUUID().toString))))
    journal
  }

  val rekisterit = new Registers {
    private val erat = system.actorOf(Props(new MockedResourceActor[ImportBatch, UUID]()))
    private val arvosanat = system.actorOf(Props(new ArvosanaActor()))
    private val opiskeluoikeudet = system.actorOf(Props(new OpiskeluoikeusActor()))
    private val opiskelijat = system.actorOf(Props(new OpiskelijaActor()))
    private val suoritukset = system.actorOf(Props(new SuoritusActor(suorituksetSeq)))

    override val eraRekisteri: ActorRef = system.actorOf(Props(new FakeAuthorizer(erat)))
    override val arvosanaRekisteri: ActorRef = system.actorOf(Props(new FakeAuthorizer(arvosanat)))
    override val opiskeluoikeusRekisteri: ActorRef = system.actorOf(Props(new FakeAuthorizer(opiskeluoikeudet)))
    override val opiskelijaRekisteri: ActorRef = system.actorOf(Props(new FakeAuthorizer(opiskelijat)))
    override val suoritusRekisteri: ActorRef = system.actorOf(Props(new FakeAuthorizer(suoritukset)))
  }
  val hakuappConfig = ServiceConfig(serviceUrl = "http://localhost/haku-app")
  val endpoint = mock[Endpoint]
  when(endpoint.request(forPattern("http://localhost/haku-app/applications/listfull?start=0&rows=2000&asId=.*"))).thenReturn((200, List(), "[]"))
  when(endpoint.request(forPattern("http://localhost/valintarekisteri/ensikertalaisuus/.*"))).thenReturn((200, List(), """{"oid":"foo","paattyi":"2014-09-01T00:00:00Z"}"""))

  val hakemukset = henkilot.map(henkilo => {
    FullHakemus(
      oid = UUID.randomUUID().toString,
      personOid = Some(henkilo),
      applicationSystemId = "foo",
      answers = Some(HakemusAnswers(Some(HakemusHenkilotiedot(Henkilotunnus = Some(henkilo))))),
      state = Some("INCOMPLETE"),
      preferenceEligibilities = Seq()
    )
  }).toSeq

  val hakemusActor = system.actorOf(Props(new HakemusActor(hakemusClient = new VirkailijaRestClient(config = hakuappConfig, aClient = Some(new AsyncHttpClient(new CapturingProvider(endpoint)))), journal = hakemukset)))

  hakemusActor ! RefreshingDone(Some(Platform.currentTime))

  val tarjontaActor = system.actorOf(Props(new Actor {
    override def receive: Receive = {
      case GetKomoQuery(oid) => sender ! KomoResponse(oid, Some(Komo(oid, Koulutuskoodi("123456"), "TUTKINTO_OHJELMA", "LUKIOKOULUTUS")))
      case a => sender ! a
    }
  }))

  val valintarekisteri = TestActorRef(new TestingValintarekisteriActor(new VirkailijaRestClient(config = ServiceConfig(serviceUrl = "http://localhost/valintarekisteri"), aClient = Some(new AsyncHttpClient(new CapturingProvider(endpoint)))), Config.mockConfig))

  val ensikertalaisuusActor = system.actorOf(Props(new EnsikertalainenActor(rekisterit.suoritusRekisteri, valintarekisteri, tarjontaActor, Config.mockConfig)))

  val resource = new OppijaResource(rekisterit, hakemusActor, ensikertalaisuusActor)

  addServlet(resource, "/*")

  override def stop(): Unit = {
    system.shutdown()
    system.awaitTermination(15.seconds)
  }
}

class TestingValintarekisteriActor(restClient: VirkailijaRestClient, config: Config) extends ValintarekisteriActor(restClient, config) {

  var requestCount: Long = 0

  override def fetchEnsimmainenVastaanotto(henkiloOid: String): Future[Option[DateTime]] = {
    requestCount = requestCount + 1
    super.fetchEnsimmainenVastaanotto(henkiloOid)
  }
}