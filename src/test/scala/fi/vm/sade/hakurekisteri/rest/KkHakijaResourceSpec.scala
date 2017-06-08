package fi.vm.sade.hakurekisteri.rest

import akka.actor.{Actor, Props}
import akka.pattern.pipe
import com.ning.http.client.AsyncHttpClient
import fi.vm.sade.hakurekisteri.acceptance.tools.HakeneetSupport
import fi.vm.sade.hakurekisteri.dates.{Ajanjakso, InFuture}
import fi.vm.sade.hakurekisteri.hakija.{Syksy, _}
import fi.vm.sade.hakurekisteri.integration._
import fi.vm.sade.hakurekisteri.integration.hakemus._
import fi.vm.sade.hakurekisteri.integration.haku.{GetHaku, Haku, HakuNotFoundException, Kieliversiot}
import fi.vm.sade.hakurekisteri.integration.henkilo.MockOppijaNumeroRekisteri
import fi.vm.sade.hakurekisteri.integration.koodisto._
import fi.vm.sade.hakurekisteri.integration.tarjonta._
import fi.vm.sade.hakurekisteri.integration.valintatulos.Ilmoittautumistila.Ilmoittautumistila
import fi.vm.sade.hakurekisteri.integration.valintatulos.Valintatila.Valintatila
import fi.vm.sade.hakurekisteri.integration.valintatulos.Vastaanottotila.Vastaanottotila
import fi.vm.sade.hakurekisteri.integration.valintatulos.{ValintaTulosQuery, _}
import fi.vm.sade.hakurekisteri.integration.ytl.YoTutkinto
import fi.vm.sade.hakurekisteri.rest.support.{AuditSessionRequest, User}
import fi.vm.sade.hakurekisteri.storage.repository.{InMemJournal, Updated}
import fi.vm.sade.hakurekisteri.suoritus.{SuoritysTyyppiQuery, VirallinenSuoritus}
import fi.vm.sade.hakurekisteri.web.kkhakija.{KkHakijaService, KkHakijaQuery, KkHakijaResource, KkHakijaUtil}
import fi.vm.sade.hakurekisteri.web.rest.support.{HakurekisteriSwagger, TestSecurity}
import org.joda.time.{DateTime, LocalDate}
import org.mockito.Mockito._
import org.scalatest.concurrent.AsyncAssertions
import org.scalatest.mock.MockitoSugar
import org.scalatra.swagger.Swagger
import org.scalatra.test.scalatest.ScalatraFunSuite

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class KkHakijaResourceSpec extends ScalatraFunSuite with HakeneetSupport with MockitoSugar with DispatchSupport with AsyncAssertions with LocalhostProperties {
  implicit val swagger: Swagger = new HakurekisteriSwagger
  implicit val security = new TestSecurity

  val endPoint = mock[Endpoint]
  val asyncProvider = new CapturingProvider(endPoint)
  val client = new VirkailijaRestClient(ServiceConfig(serviceUrl = "http://localhost/haku-app"), aClient = Some(new AsyncHttpClient(asyncProvider)))
  val hakemusService = new HakemusService(client, MockOppijaNumeroRekisteri)
  val tarjontaMock = system.actorOf(Props(new MockedTarjontaActor()))
  val hakuMock = system.actorOf(Props(new MockedHakuActor()))
  val suoritusMock = system.actorOf(Props(new MockedSuoritusActor()))
  val valintaTulosMock = system.actorOf(Props(new MockedValintaTulosActor()))
  val valintaRekisteri = system.actorOf(Props(new MockedValintarekisteriActor()))
  val koodistoMock = system.actorOf(Props(new MockedKoodistoActor()))
  val hakupalvelu = new Hakupalvelu() {
    override def getHakijat(q: HakijaQuery): Future[Seq[Hakija]] = Future.successful(Seq())
    override def getHakukohdeOids(hakukohderyhma: String, hakuOid: String): Future[Seq[String]] = Future.successful(Seq())
  }

  val service = new KkHakijaService(hakemusService, Hakupalvelu, tarjontaMock, hakuMock, koodistoMock, suoritusMock, valintaTulosMock, valintaRekisteri)
  val resource = new KkHakijaResource(service)
  addServlet(resource, "/")


  override def beforeEach() {
    super.beforeEach()
    reset(endPoint)
  }


  test("should return 200 OK") {
    when(endPoint.request(forPattern(".*listfull.*"))).thenReturn((200, List(), "[]"))
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

  test("should not return results if user not in hakukohde organization hierarchy") {
    when(endPoint.request(forPattern(".*applications/byPersonOid.*")))
      .thenReturn((200, List(), getJson("applicationsByPersonOid")))

    val hakijat = Await.result(
      service.getKkHakijat(KkHakijaQuery(Some("1.2.246.562.24.81468276424"), None, None, None, None, Hakuehto.Kaikki, 1, Some(testUser("test", "1.1"))), 1), 15.seconds
    )

    hakijat.size should be (0)
  }

  test("should return five hakijas") {
    when(endPoint.request(forPattern(".*listfull.*")))
      .thenReturn((200, List(), getJson("byApplicationOption")))

    val hakijat = Await.result(
      service.getKkHakijat(KkHakijaQuery(None, None, None, Some("1.2.246.562.20.649956391810"), None, Hakuehto.Kaikki, 1, Some(testUser("test", "1.2.246.562.10.00000000001"))), 1), 150.seconds
    )

    hakijat.size should be (5)
  }

  test("should return one hyvaksytty hakija") {
    when(endPoint.request(forPattern(".*listfull.*")))
      .thenReturn((200, List(), getJson("byApplicationOption")))

    val hakijat = Await.result(
      service.getKkHakijat(KkHakijaQuery(None, None, None, Some("1.11.2"), None, Hakuehto.Hyvaksytyt, 1, Some(testUser("test", "1.2.246.562.10.00000000001"))), 1), 15.seconds
    )

    hakijat.size should be (1)
  }

  test("should convert ilmoittautumiset into sequence in syksyn haku") {
    val haku = Haku(
      nimi = Kieliversiot(fi = Some("joo"), sv = None, en = None),
      oid = "1.2.3",
      aika = Ajanjakso(alkuPaiva = new LocalDate(), loppuPaiva = None),
      kausi = "kausi_s#1",
      vuosi = 2014,
      koulutuksenAlkamiskausi = Some("kausi_k#1"),
      koulutuksenAlkamisvuosi = Some(2015),
      kkHaku = true,
      viimeinenHakuaikaPaattyy = Some(new DateTime()),
      None
    )

    val hakukohteenKoulutukset: HakukohteenKoulutukset = HakukohteenKoulutukset("1.5.1", Some("joku tunniste"), Seq(koulutus1))

    val sijoitteluTulos = new SijoitteluTulos {
      override def ilmoittautumistila(hakemus: String, kohde: String): Option[Ilmoittautumistila] = Some(Ilmoittautumistila.LASNA_KOKO_LUKUVUOSI)
      override def vastaanottotila(hakemus: String, kohde: String): Option[Vastaanottotila] = Some(Vastaanottotila.KESKEN)
      override def valintatila(hakemus: String, kohde: String): Option[Valintatila] = Some(Valintatila.KESKEN)
      override def pisteet(hakemus: String, kohde: String): Option[BigDecimal] = Some(BigDecimal(4.0))
    }
    val ilmoittautumiset: Seq[Lasnaolo] = Await.result(KkHakijaUtil.getLasnaolot(sijoitteluTulos, "1.5.1", "", hakukohteenKoulutukset.koulutukset), 15.seconds)

    ilmoittautumiset should (contain(Lasna(Syksy(2015))) and contain(Lasna(Kevat(2015))))
  }

  test("should convert ilmoittautumiset into sequence in kevään haku") {
    val haku = Haku(
      nimi = Kieliversiot(fi = Some("joo"), sv = None, en = None),
      oid = "1.2.3",
      aika = Ajanjakso(alkuPaiva = new LocalDate(), loppuPaiva = None),
      kausi = "kausi_k#1",
      vuosi = 2015,
      koulutuksenAlkamiskausi = Some("kausi_s#1"),
      koulutuksenAlkamisvuosi = Some(2015),
      kkHaku = true,
      viimeinenHakuaikaPaattyy = Some(new DateTime()),
      None
    )

    val hakukohteenKoulutukset: HakukohteenKoulutukset = HakukohteenKoulutukset("1.5.1", Some("joku tunniste"), Seq(koulutus2))

    val sijoitteluTulos = new SijoitteluTulos {
      override def ilmoittautumistila(hakemus: String, kohde: String): Option[Ilmoittautumistila] = Some(Ilmoittautumistila.LASNA_SYKSY)
      override def vastaanottotila(hakemus: String, kohde: String): Option[Vastaanottotila] = Some(Vastaanottotila.KESKEN)
      override def valintatila(hakemus: String, kohde: String): Option[Valintatila] = Some(Valintatila.KESKEN)
      override def pisteet(hakemus: String, kohde: String): Option[BigDecimal] = Some(BigDecimal(4.0))
    }
    val ilmoittautumiset = Await.result(KkHakijaUtil.getLasnaolot(sijoitteluTulos, "1.5.1", "", hakukohteenKoulutukset.koulutukset), 15.seconds)

    ilmoittautumiset should (contain(Lasna(Syksy(2015))) and contain(Poissa(Kevat(2016))))
  }

  test("should convert ilmoittautumiset into sequence in syksy haku but koulutus start season in next year syksy") {
    val haku = Haku(
      nimi = Kieliversiot(fi = Some("joo"), sv = None, en = None),
      oid = "1.2.3",
      aika = Ajanjakso(alkuPaiva = new LocalDate(), loppuPaiva = None),
      kausi = "kausi_s#1",
      vuosi = 2015,
      koulutuksenAlkamiskausi = Some("kausi_s#1"),
      koulutuksenAlkamisvuosi = Some(2016),
      kkHaku = true,
      viimeinenHakuaikaPaattyy = Some(new DateTime()),
      None
    )

    val koulutusSyksy = Hakukohteenkoulutus("1.5.6", "123456", Some("AABB5tga"), Some(kausiKoodiS), Some(2016), None)
    val hakukohteenKoulutukset: HakukohteenKoulutukset = HakukohteenKoulutukset("1.5.1", Some("joku tunniste"), Seq(koulutusSyksy))

    val sijoitteluTulos = new SijoitteluTulos {
      override def ilmoittautumistila(hakemus: String, kohde: String): Option[Ilmoittautumistila] = Some(Ilmoittautumistila.LASNA_KOKO_LUKUVUOSI)
      override def vastaanottotila(hakemus: String, kohde: String): Option[Vastaanottotila] = Some(Vastaanottotila.KESKEN)
      override def valintatila(hakemus: String, kohde: String): Option[Valintatila] = Some(Valintatila.KESKEN)
      override def pisteet(hakemus: String, kohde: String): Option[BigDecimal] = Some(BigDecimal(4.0))
    }
    val ilmoittautumiset = Await.result(KkHakijaUtil.getLasnaolot(sijoitteluTulos, "1.5.1", "", hakukohteenKoulutukset.koulutukset), 15.seconds)

    ilmoittautumiset should (contain(Lasna(Syksy(2016))) and contain(Lasna(Kevat(2017))))
  }

  test("should show turvakielto true from hakemus") {
    when(endPoint.request(forPattern(".*applications/byPersonOid.*")))
      .thenReturn((200, List(), getJson("applicationsByPersonOid")))

    val hakijat = Await.result(service.getKkHakijat(KkHakijaQuery(Some("1.2.246.562.24.81468276424"), None, None, None, None, Hakuehto.Kaikki, 1, Some(testUser("test", "1.2.246.562.10.00000000001"))), 1), 15.seconds)

    hakijat.head.turvakielto should be (true)
  }

  test("should return turvakielto false from hakemus") {
    when(endPoint.request(forPattern(".*applications/byPersonOid.*")))
      .thenReturn((200, List(), getJson("applicationsByPersonOid")))

    val hakijat = Await.result(service.getKkHakijat(KkHakijaQuery(Some("1.2.246.562.24.81468276424"), None, None, None, None, Hakuehto.Kaikki, 1, Some(testUser("test", "1.2.246.562.10.00000000001"))), 1), 15.seconds)

    hakijat(1).turvakielto should be (false)
  }

  test("should return empty hakukelpoisuus by default") {
    when(endPoint.request(forPattern(".*applications/byPersonOid.*")))
      .thenReturn((200, List(), getJson("applicationsByPersonOid")))

    val hakijat = Await.result(service.getKkHakijat(KkHakijaQuery(Some("1.2.246.562.24.81468276424"), None, None, None, None, Hakuehto.Kaikki, 1, Some(testUser("test", "1.2.246.562.10.00000000001"))), 1), 15.seconds)

    hakijat.head.hakemukset.exists(_.hKelpoisuus == "") should be (true)
  }

  test("should return hakukelpoisuus from hakemus") {
    when(endPoint.request(forPattern(".*applications/byPersonOid.*")))
      .thenReturn((200, List(), getJson("applicationsByPersonOid")))

    val hakijat = Await.result(service.getKkHakijat(KkHakijaQuery(Some("1.2.246.562.24.81468276424"), None, None, None, None, Hakuehto.Kaikki, 1, Some(testUser("test", "1.2.246.562.10.00000000001"))), 1), 15.seconds)

    hakijat.head.hakemukset.exists(_.hKelpoisuus == "NOT_CHECKED") should be (true)
  }

  test("should return kotikunta default if it is not defined in hakemus") {
    when(endPoint.request(forPattern(".*applications/byPersonOid.*")))
      .thenReturn((200, List(), getJson("applicationsByPersonOid")))

    val hakijat = Await.result(service.getKkHakijat(KkHakijaQuery(Some("1.2.246.562.24.81468276424"), None, None, None, None, Hakuehto.Kaikki, 1, Some(testUser("test", "1.2.246.562.10.00000000001"))), 1), 15.seconds)

    hakijat.head.kotikunta should be ("999")
  }

  test("should return kotikunta from hakemus") {
    when(endPoint.request(forPattern(".*applications/byPersonOid.*")))
      .thenReturn((200, List(), getJson("applicationsByPersonOid")))

    val hakijat = Await.result(service.getKkHakijat(KkHakijaQuery(Some("1.2.246.562.24.81468276424"), None, None, None, None, Hakuehto.Kaikki, 1, Some(testUser("test", "1.2.246.562.10.00000000001"))), 1), 15.seconds)

    hakijat(1).kotikunta should be ("049")
  }

  test("should return postitoimipaikka") {
    when(endPoint.request(forPattern(".*applications/byPersonOid.*")))
      .thenReturn((200, List(), getJson("applicationsByPersonOid")))

    val hakijat = Await.result(service.getKkHakijat(KkHakijaQuery(Some("1.2.246.562.24.81468276424"), None, None, None, None, Hakuehto.Kaikki, 1, Some(testUser("test", "1.2.246.562.10.00000000001"))), 1), 15.seconds)

    hakijat.head.postitoimipaikka should be ("Posti_02140")
  }

  test("should not return koulutuksenAlkamiskausi, koulutuksenAlkamisvuosi, koulutuksenAlkamisPvms") {
    when(endPoint.request(forPattern(".*applications/byPersonOid.*")))
      .thenReturn((200, List(), getJson("applicationsByPersonOid")))

    val hakijat = Await.result(service.getKkHakijat(KkHakijaQuery(Some("1.2.246.562.24.81468276424"), None, None, None, None, Hakuehto.Kaikki, 1, Some(testUser("test", "1.2.246.562.10.00000000001"))), 1), 15.seconds)

    val koulutus: Hakukohteenkoulutus = hakijat.head.hakemukset.head.hakukohteenKoulutukset.head
    koulutus.koulutuksenAlkamiskausi should be (None)
    koulutus.koulutuksenAlkamisvuosi should be (None)
    koulutus.koulutuksenAlkamisPvms should be (None)
  }

  test("should not return hakemus of expired haku") {
    when(endPoint.request(forPattern(".*applications/byPersonOid.*")))
      .thenReturn((200, List(), getJson("applicationsByPersonOid")))

    val hakijat = Await.result(service.getKkHakijat(KkHakijaQuery(Some("1.2.246.562.24.81468276424"), None, None, None, None, Hakuehto.Kaikki, 1, Some(testUser("test", "1.2.246.562.10.00000000001"))), 1), 15.seconds)

    hakijat.size should be (2)
  }

  test("should not have FI as default aidinkieli, asiointikieli or koulusivistyskieli") {
    when(endPoint.request(forPattern(".*applications/byPersonOid.*")))
      .thenReturn((200, List(), getJson("applicationsByPersonOid")))

    val hakijat = Await.result(service.getKkHakijat(KkHakijaQuery(Some("1.2.246.562.24.81468276424"), None, None, None, None, Hakuehto.Kaikki, 1, Some(testUser("test", "1.2.246.562.10.00000000001"))), 1), 15.seconds)

    hakijat.last.aidinkieli should be ("99")
    hakijat.last.asiointikieli should be ("9") // Default is not empty!
    hakijat.last.koulusivistyskieli should be ("99")
  }

  test("should return default kansalaisuus, asuinmaa, kotikunta") {
    when(endPoint.request(forPattern(".*applications/byPersonOid.*")))
      .thenReturn((200, List(), getJson("applicationsByPersonOid")))

    val hakijat = Await.result(service.getKkHakijat(KkHakijaQuery(Some("1.2.246.562.24.81468276424"), None, None, None, None, Hakuehto.Kaikki, 1, Some(testUser("test", "1.2.246.562.10.00000000001"))), 1), 15.seconds)

    hakijat.last.kansalaisuus should be ("999")
    hakijat.last.maa should be ("999")
    hakijat.head.kotikunta should be ("999")
  }


  def testUser(user: String, organisaatioOid: String) = new User {
    override val username: String = user
    override val auditSession = AuditSessionRequest(user, Set(organisaatioOid), "", "")
    override def orgsFor(action: String, resource: String): Set[String] = Set(organisaatioOid)
  }

  import fi.vm.sade.hakurekisteri.suoritus.yksilollistaminen._

  val haku1 = RestHaku(Some("1.2"), List(RestHakuAika(1L, Some(2L))), Map("fi" -> "testihaku"), "kausi_s#1", 2014, Some("kausi_k#1"), Some(2015), Some("haunkohdejoukko_12#1"), None, "JULKAISTU")
  val kausiKoodiK = TarjontaKoodi(Some("K"))
  val kausiKoodiS = TarjontaKoodi(Some("S"))
  val koulutus1 = Hakukohteenkoulutus("1.5.6", "123456", Some("AABB5tga"), Some(kausiKoodiK), Some(2015), None)
  val koulutus2 = Hakukohteenkoulutus("1.5.6", "123457", Some("asdfASDF4"), Some(kausiKoodiS), Some(2015), None)
  val suoritus1 = VirallinenSuoritus(YoTutkinto.yotutkinto, YoTutkinto.YTL, "VALMIS", new LocalDate(), "1.2.3", Ei, "FI", None, true, "1")

  def seq2journal(s: Seq[FullHakemus]) = {
    val journal = new InMemJournal[FullHakemus, String]
    s.foreach((h: FullHakemus) => {
      journal.addModification(Updated[FullHakemus, String](h.identify(h.oid)))
    })
    journal
  }

  class MockedTarjontaActor extends Actor {
    override def receive: Actor.Receive = {
      case oid: HakukohdeOid =>  sender ! HakukohteenKoulutukset(oid.oid, Some("joku tunniste"), Seq(koulutus1))
    }
  }

  class MockedHakuActor extends Actor {
    override def receive: Actor.Receive = {
      case q: GetHaku if q.oid == "1.3.10" => Future.failed(HakuNotFoundException(s"haku not found with oid ${q.oid}")) pipeTo sender
      case q: GetHaku =>  sender ! Haku(haku1)(InFuture)
    }
  }

  class MockedSuoritusActor extends Actor {
    override def receive: Actor.Receive = {
      case q: SuoritysTyyppiQuery => sender ! Seq(suoritus1)
    }
  }

  class MockedValintarekisteriActor extends Actor {
    override def receive: Actor.Receive = {
      case _ =>
        sender ! Seq.empty
    }
  }
  class MockedValintaTulosActor extends Actor {
    override def receive: Actor.Receive = {
      case q: ValintaTulosQuery if q.hakuOid == FullHakemus1.applicationSystemId =>
        sender ! new SijoitteluTulos {
          override def ilmoittautumistila(hakemus: String, kohde: String): Option[Ilmoittautumistila] = Some(Ilmoittautumistila.EI_TEHTY)
          override def vastaanottotila(hakemus: String, kohde: String): Option[Vastaanottotila] = Some(Vastaanottotila.KESKEN)
          override def valintatila(hakemus: String, kohde: String): Option[Valintatila] = Some(Valintatila.HYVAKSYTTY)
          override def pisteet(hakemus: String, kohde: String): Option[BigDecimal] = Some(BigDecimal(4.0))
        }
      case q: ValintaTulosQuery =>
        sender ! new SijoitteluTulos {
          override def ilmoittautumistila(hakemus: String, kohde: String): Option[Ilmoittautumistila] = None
          override def vastaanottotila(hakemus: String, kohde: String): Option[Vastaanottotila] = None
          override def valintatila(hakemus: String, kohde: String): Option[Valintatila] = None
          override def pisteet(hakemus: String, kohde: String): Option[BigDecimal] = None
        }
    }
  }

  class MockedKoodistoActor extends Actor {
    override def receive: Actor.Receive = {
      case q: GetRinnasteinenKoodiArvoQuery => sender ! "246"
      case q: GetKoodi =>
        sender ! Some(Koodi(q.koodiUri.split("_").last.split("#").head.toUpperCase, q.koodiUri, Koodisto(q.koodistoUri), Seq(KoodiMetadata(q.koodiUri.capitalize, "FI"))))
      case q: GetKoodistoKoodiArvot => q.koodistoUri match {
        case "oppiaineetyleissivistava" => sender ! KoodistoKoodiArvot(
          koodistoUri = "oppiaineetyleissivistava",
          arvot = Seq("AI", "A1", "A12", "A2", "A22", "B1", "B2", "B22", "B23", "B3", "B32", "B33", "BI", "FI","FY", "GE", "HI", "KE", "KO", "KS", "KT", "KU", "LI", "MA", "MU", "PS", "TE", "YH")
        )
      }
    }
  }

  override def stop(): Unit = {
    Await.result(system.terminate(), 15.seconds)
    super.stop()
  }
}

