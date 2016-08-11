package fi.vm.sade.hakurekisteri.rest

import java.util.UUID

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.pipe
import akka.testkit.TestActorRef
import com.ning.http.client.AsyncHttpClient
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.acceptance.tools.FakeAuthorizer
import fi.vm.sade.hakurekisteri.arvosana.{Arvosana, ArvosanaJDBCActor, ArvosanaTable}
import fi.vm.sade.hakurekisteri.batchimport.ImportBatch
import fi.vm.sade.hakurekisteri.ensikertalainen.{EnsikertalainenActor, Testihaku}
import fi.vm.sade.hakurekisteri.integration._
import fi.vm.sade.hakurekisteri.integration.hakemus._
import fi.vm.sade.hakurekisteri.integration.haku.{GetHaku, HakuNotFoundException}
import fi.vm.sade.hakurekisteri.integration.tarjonta.{GetKomoQuery, Komo, KomoResponse, Koulutuskoodi}
import fi.vm.sade.hakurekisteri.integration.valintarekisteri.{EnsimmainenVastaanotto, ValintarekisteriActor}
import fi.vm.sade.hakurekisteri.opiskelija.{Opiskelija, OpiskelijaJDBCActor, OpiskelijaTable}
import fi.vm.sade.hakurekisteri.opiskeluoikeus.{Opiskeluoikeus, OpiskeluoikeusJDBCActor, OpiskeluoikeusTable}
import fi.vm.sade.hakurekisteri.oppija.Oppija
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriJsonSupport, JDBCJournal, Registers, User}
import fi.vm.sade.hakurekisteri.storage.repository.{InMemJournal, Updated}
import fi.vm.sade.hakurekisteri.suoritus.{SuoritusActor, VirallinenSuoritus, yksilollistaminen}
import fi.vm.sade.hakurekisteri.test.tools.{FutureWaiting, MockedResourceActor}
import fi.vm.sade.hakurekisteri.tools.ItPostgres
import fi.vm.sade.hakurekisteri.web.oppija.{OppijaResource, OppijatPostSize}
import fi.vm.sade.hakurekisteri.web.rest.support.{HakurekisteriSwagger, TestSecurity}
import fi.vm.sade.utils.tcp.ChooseFreePort
import org.joda.time.LocalDate
import org.json4s.Extraction.decompose
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization.read
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatra.swagger.Swagger
import org.scalatra.test.scalatest.ScalatraFunSuite

import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.implicitConversions
import scala.util.Random

class OppijaResourceSpec extends OppijaResourceSetup with LocalhostProperties{

  implicit val formats = HakurekisteriJsonSupport.format

  private val OK: Int = 200
  private val BAD_REQUEST: Int = 400

  test("OppijaResource should return 200") {
    get("/?haku=1") {
      response.status should be(OK)
    }
  }

  test("OppijaResource should return 400 if no parameters are given") {
    get("/") {
      response.status should be(400)
    }
  }

  test("OppijaResource should return 10001 oppijas with ensikertalainen false") {
    waitFuture(resource.fetchOppijat(HakemusQuery(Some("1.2.246.562.6.00000000001"), None, None), Testihaku.oid))(oppijat => {
      val expectedSize: Int = 10001
      oppijat.length should be(expectedSize)
      oppijat.foreach(o => o.ensikertalainen should be(Some(true)))
    })
  }

  test("OppijaResource should return oppija with ensikertalainen true") {
    get("/1.2.246.562.24.00000000001?haku=1.2.3.4") {
      response.status should be(OK)

      val oppija = read[Oppija](response.body)
      oppija.ensikertalainen should be (Some(true))
    }
  }

  test("OppijaResource should not return ensikertalaisuus when haku parameter is not given") {
    get("/1.2.246.562.24.00000000001") {
      response.status should be(OK)

      val oppija = read[Oppija](response.body)
      oppija.oppijanumero should be("1.2.246.562.24.00000000001")
      oppija.ensikertalainen should be (None)
    }
  }

  test("OppijaResource should not cache ensikertalaisuus") {
    valintarekisteri.underlyingActor.requestCount = 0
    get("/?haku=1.2.246.562.6.00000000001") {
      get("/?haku=1.2.246.562.6.00000000001") {
        val expectedCount: Int = 2
        valintarekisteri.underlyingActor.requestCount should be (expectedCount)
      }
    }
  }

  test("OppijaResource should tell ensikertalaisuus true also for oppija without hetu") {
    waitFuture(resource.fetchOppijatFor(Seq(FullHakemus(
      oid = "1.2.246.562.11.00000000001",
      personOid = Some("1.2.246.562.24.00000000002"),
      applicationSystemId = "1.2.246.562.6.00000000001",
      answers = Some(HakemusAnswers(Some(HakemusHenkilotiedot()))),
      state = Some("INCOMPLETE"),
      preferenceEligibilities = Seq()
    )), Testihaku.oid))((s: Seq[Oppija]) => {
      s.head.ensikertalainen should be(Some(true))
    })
  }

  test("OppijaResource should 200 when a list of person oids is sent as POST") {
    post("/?haku=1.2.3.4", """["1.2.246.562.24.00000000002"]""") {
      response.status should be (OK)
    }
  }

  test("OppijaResource should return 400 if too many person oids is sent as POST") {
    val json = decompose((1 to (OppijatPostSize.maxOppijatPostSize + 1)).map(i => s"1.2.246.562.24.$i"))

    post("/?haku=1.2.3.4", compact(json)) {
      response.status should be (BAD_REQUEST)
      response.body should include("too many person oids")
    }
  }

  test("OppijaResource should return 400 if invalid person oids is sent as POST") {
    post("/?haku=1.2.3.4", """["foo","1.2.246.562.24.00000000002"]""") {
      response.status should be (BAD_REQUEST)
      response.body should include("person oid must start with 1.2.246.562.24.")
    }
  }

  test("OppijaResource should return 100 oppijas when 100 person oids is sent as POST") {
    val json = decompose(henkilot.take(100).map(i => s"1.2.246.562.24.$i"))

    post("/?haku=1.2.3.4", compact(json)) {
      val oppijat = read[Seq[Oppija]](response.body)
      oppijat.size should be (100)
    }
  }

  test("OppijaResource should return an empty oppija object if no matching oppija found when person oid is sent as POST") {
    post("/?haku=1.2.3.4", """["1.2.246.562.24.00000000010"]""") {
      val oppijat = read[Seq[Oppija]](response.body)

      oppijat.size should be (1)

      val o = oppijat.head
      o.oppijanumero should be("1.2.246.562.24.00000000010")
      o.opiskelu.size should be (0)
      o.suoritukset.size should be(0)
      o.opiskeluoikeudet.size should be (0)
    }
  }

  test("OppijaResource should return 404 if haku not found") {
    get("/?haku=notfound") {
      response.status should be (404)
    }
  }

}

abstract class OppijaResourceSetup extends ScalatraFunSuite with MockitoSugar with DispatchSupport with FutureWaiting {
  implicit val system = ActorSystem("oppija-resource-test-system")
  implicit val security = new TestSecurity
  implicit val user: User = security.TestUser
  implicit val swagger: Swagger = new HakurekisteriSwagger

  val henkilot: Set[String] = {
    var oids: Set[String] = Set("1.2.246.562.24.00000000001")
    while (oids.size < 10001) {
      oids = oids + s"1.2.246.562.24.${new Random().nextInt(99999999).toString.padTo(11, '0')}"
    }
    oids
  }

  val suorituksetSeq = henkilot.map(henkilo =>
    VirallinenSuoritus(
      "1.2.246.562.5.00000000001",
      "1.2.246.562.10.00000000001",
      "VALMIS",
      new LocalDate(2001, 1, 1),
      henkilo,
      yksilollistaminen.Ei,
      "FI",
      None,
      vahv = true,
      ""
    )
  ).toSeq

  implicit def seq2journal[R <: fi.vm.sade.hakurekisteri.rest.support.Resource[UUID, R]](s: Seq[R]): InMemJournal[R, UUID] = {
    val journal = new InMemJournal[R, UUID]
    s.foreach((resource: R) => journal.addModification(Updated(resource.identify(UUID.randomUUID()))))
    journal
  }

  implicit def seq2journalString[R <: fi.vm.sade.hakurekisteri.rest.support.Resource[String, R]](s: Seq[R]): InMemJournal[R, String] = {
    val journal = new InMemJournal[R, String]
    s.foreach((resource: R) => journal.addModification(Updated(resource.identify(UUID.randomUUID().toString))))
    journal
  }

  val portChooser = new ChooseFreePort
  val itDb = new ItPostgres(portChooser)
  itDb.start()
  implicit val database = Database.forURL(s"jdbc:postgresql://localhost:${portChooser.chosenPort}/suoritusrekisteri")

  val arvosanaJournal = new JDBCJournal[Arvosana, UUID, ArvosanaTable](TableQuery[ArvosanaTable])
  val opiskeluoikeusJournal = new JDBCJournal[Opiskeluoikeus, UUID, OpiskeluoikeusTable](TableQuery[OpiskeluoikeusTable])
  val opiskelijaJournal = new JDBCJournal[Opiskelija, UUID, OpiskelijaTable](TableQuery[OpiskelijaTable])

  val rekisterit = new Registers {
    private val erat = system.actorOf(Props(new MockedResourceActor[ImportBatch, UUID]()))
    private val arvosanat = system.actorOf(Props(new ArvosanaJDBCActor(arvosanaJournal, 1)))
    private val opiskeluoikeudet = system.actorOf(Props(new OpiskeluoikeusJDBCActor(opiskeluoikeusJournal, 1)))
    private val opiskelijat = system.actorOf(Props(new OpiskelijaJDBCActor(opiskelijaJournal, 1)))
    private val suoritukset = system.actorOf(Props(new SuoritusActor(suorituksetSeq)))

    override val eraRekisteri: ActorRef = system.actorOf(Props(new FakeAuthorizer(erat)))
    override val arvosanaRekisteri: ActorRef = system.actorOf(Props(new FakeAuthorizer(arvosanat)))
    override val opiskeluoikeusRekisteri: ActorRef = system.actorOf(Props(new FakeAuthorizer(opiskeluoikeudet)))
    override val opiskelijaRekisteri: ActorRef = system.actorOf(Props(new FakeAuthorizer(opiskelijat)))
    override val suoritusRekisteri: ActorRef = system.actorOf(Props(new FakeAuthorizer(suoritukset)))
  }
  val hakuappConfig = ServiceConfig(serviceUrl = "http://localhost/haku-app")
  val endpoint = mock[Endpoint]
  when(endpoint.request(forPattern("http://localhost/haku-app/applications/listfull?start=0&rows=2000&asId=.*"))).
    thenReturn((200, List(), "[]"))

  val hakemukset: Seq[FullHakemus] = henkilot.map(henkilo => {
    FullHakemus(
      oid = UUID.randomUUID().toString,
      personOid = Some(henkilo),
      applicationSystemId = "1.2.246.562.6.00000000001",
      answers = Some(HakemusAnswers(Some(HakemusHenkilotiedot(Henkilotunnus = Some(henkilo))))),
      state = Some("INCOMPLETE"),
      preferenceEligibilities = Seq()
    )
  }).toSeq

  val hakemusActor = system.actorOf(Props(new HakemusActor(
    hakemusClient = new VirkailijaRestClient(config = hakuappConfig, aClient = Some(new AsyncHttpClient(new CapturingProvider(endpoint)))),
    journal = hakemukset
  )))

  hakemusActor ! RefreshingDone(Some(Platform.currentTime))

  val tarjontaActor = system.actorOf(Props(new Actor {
    override def receive: Receive = {
      case GetKomoQuery(oid) => sender ! KomoResponse(oid, Some(Komo(oid, Koulutuskoodi("123456"), "TUTKINTO_OHJELMA", "LUKIOKOULUTUS")))
      case a => sender ! a
    }
  }))

  val valintarekisteri = TestActorRef(new TestingValintarekisteriActor(
    new VirkailijaRestClient(config = ServiceConfig(serviceUrl = "http://localhost/valinta-tulos-service")),
    Config.mockConfig)
  )

  val ensikertalaisuusActor = system.actorOf(Props(new EnsikertalainenActor(
    rekisterit.suoritusRekisteri,
    rekisterit.opiskeluoikeusRekisteri,
    valintarekisteri,
    tarjontaActor,
    system.actorOf(Props(new Actor {
      override def receive: Receive = {
        case GetHaku("notfound") => Future.failed(HakuNotFoundException("haku not found")) pipeTo sender
        case q: GetHaku => sender ! Testihaku
      }
    })),
    hakemusActor,
    Config.mockConfig
  )))

  val resource = new OppijaResource(rekisterit, hakemusActor, ensikertalaisuusActor)

  addServlet(resource, "/*")

  override def stop(): Unit = {
    Await.result(system.terminate(), 15.seconds)
  }
}

class TestingValintarekisteriActor(restClient: VirkailijaRestClient, config: Config) extends ValintarekisteriActor(restClient, config) {

  var requestCount: Long = 0

  override def fetchEnsimmainenVastaanotto(henkiloOids: Set[String], koulutuksenAlkamiskausi: String): Future[Seq[EnsimmainenVastaanotto]] = {
    requestCount = requestCount + 1
    Future.successful(henkiloOids.map(EnsimmainenVastaanotto(_, None)).toSeq)
  }
}
