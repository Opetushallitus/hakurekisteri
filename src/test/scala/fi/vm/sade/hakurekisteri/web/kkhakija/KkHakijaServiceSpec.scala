package fi.vm.sade.hakurekisteri.web.kkhakija

import akka.actor.{Actor, Props}
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.acceptance.tools.HakeneetSupport
import fi.vm.sade.hakurekisteri.hakija.{Syksy, _}
import fi.vm.sade.hakurekisteri.integration._
import fi.vm.sade.hakurekisteri.integration.hakemus._
import fi.vm.sade.hakurekisteri.integration.henkilo.MockOppijaNumeroRekisteri
import fi.vm.sade.hakurekisteri.integration.koodisto._
import fi.vm.sade.hakurekisteri.integration.organisaatio.OrganisaatioActorRef
import fi.vm.sade.hakurekisteri.integration.tarjonta._
import fi.vm.sade.hakurekisteri.integration.valintaperusteet.ValintaperusteetServiceMock
import fi.vm.sade.hakurekisteri.integration.valintarekisteri.{Maksuntila, ValintarekisteriActorRef}
import fi.vm.sade.hakurekisteri.integration.valintatulos._
import fi.vm.sade.hakurekisteri.integration.ytl.YoTutkinto
import fi.vm.sade.hakurekisteri.rest.support.{AuditSessionRequest, User}
import fi.vm.sade.hakurekisteri.storage.repository.{InMemJournal, Updated}
import fi.vm.sade.hakurekisteri.suoritus.VirallinenSuoritus
import fi.vm.sade.hakurekisteri.suoritus.yksilollistaminen._
import fi.vm.sade.utils.slf4j.Logging
import org.joda.time.LocalDate
import org.mockito.Mockito._
import org.scalatest.Assertion
import org.scalatest.concurrent.Waiters
import org.scalatest.mockito.MockitoSugar
import org.scalatra.test.scalatest.ScalatraFunSuite
import org.springframework.security.cas.authentication.CasAuthenticationToken

import scala.concurrent.Await
import scala.concurrent.duration._

class KkHakijaServiceSpec
    extends ScalatraFunSuite
    with HakeneetSupport
    with MockitoSugar
    with DispatchSupport
    with Waiters
    with LocalhostProperties
    with Logging {
  private val endPoint = mock[Endpoint]
  private val hakuappClient = new VirkailijaRestClient(
    ServiceConfig(serviceUrl = "http://localhost/haku-app"),
    aClient = Some(new CapturingAsyncHttpClient(endPoint))
  )
  private val ataruClient = new VirkailijaRestClient(
    ServiceConfig(serviceUrl = "http://localhost/lomake-editori"),
    aClient = Some(new CapturingAsyncHttpClient(endPoint))
  )
  private val tarjontaMock = new TarjontaActorRef(system.actorOf(Props(new MockedTarjontaActor())))
  private val organisaatioMock: OrganisaatioActorRef = new OrganisaatioActorRef(
    system.actorOf(Props(new MockedOrganisaatioActor()))
  )
  private val hakemusService = new HakemusService(
    hakuappClient,
    ataruClient,
    tarjontaMock,
    organisaatioMock,
    MockOppijaNumeroRekisteri
  )

  private val haku1 = RestHaku(
    Some("1.2"),
    List(RestHakuAika(1L, Some(2L))),
    Map("fi" -> "testihaku"),
    "kausi_s#1",
    "hakutapa_01#1",
    2014,
    Some("kausi_k#1"),
    Some(2015),
    Some("haunkohdejoukko_12#1"),
    None,
    "JULKAISTU",
    "hakutyyppi_01#1"
  )
  private val kausiKoodiS = TarjontaKoodi(Some("S"))
  private val koulutus2 =
    Hakukohteenkoulutus(
      "1.5.6",
      "123457",
      Some("asdfASDF4"),
      Some(kausiKoodiS),
      Some(2015),
      None,
      Koulutusohjelma(Map.empty)
    )
  private val suoritus1 = VirallinenSuoritus(
    YoTutkinto.yotutkinto,
    YoTutkinto.YTL,
    "VALMIS",
    new LocalDate(),
    "1.2.3",
    Ei,
    "FI",
    None,
    true,
    "1"
  )

  private val hakuMock = system.actorOf(Props(new MockedHakuActor(haku1)))
  private val suoritusMock = system.actorOf(Props(new MockedSuoritusActor(suoritus1)))
  private val personOidWithLukuvuosimaksu = "1.2.246.562.20.96296215716"
  private val paymentRequiredHakukohdeWithMaksettu = "1.2.246.562.20.49219384432"
  private val paymentRquiredHakukohdeWithoutPayment = "1.2.246.562.20.95810447722"
  private val noPaymentRequiredHakukohdeButMaksettu = "1.2.246.562.20.95810998877"
  private val koodistoMock = new KoodistoActorRef(system.actorOf(Props(new MockedKoodistoActor())))
  private val valintaperusteetMock = new ValintaperusteetServiceMock

  private val valintaTulosMock = ValintaTulosActorRef(system.actorOf(Props(new Actor {
    override def receive: Receive = {
      case HakemuksenValintatulos(hakuOid, _) => sender ! SijoitteluTulos(hakuOid, Seq())
      case HaunValintatulos("1.1") =>
        sender ! SijoitteluTulos(
          "1.1",
          ValintaTulos(
            "1.25.1",
            Seq(
              ValintaTulosHakutoive(
                "1.11.2",
                "",
                Valintatila.HYVAKSYTTY,
                Vastaanottotila.KESKEN,
                HakutoiveenIlmoittautumistila(Ilmoittautumistila.EI_TEHTY),
                None,
                "1.2.jonoOid"
              )
            )
          )
        )
      case HaunValintatulos(hakuOid) => sender ! SijoitteluTulos(hakuOid, Seq())
    }
  })))
  private val valintaRekisteri = new ValintarekisteriActorRef(
    system.actorOf(
      Props(
        new MockedValintarekisteriActor(
          personOidWithLukuvuosimaksu = personOidWithLukuvuosimaksu,
          paymentRequiredHakukohdeWithMaksettu = paymentRequiredHakukohdeWithMaksettu,
          noPaymentRequiredHakukohdeButMaksettu = noPaymentRequiredHakukohdeButMaksettu
        )
      )
    )
  )
  private val service = new KkHakijaService(
    hakemusService,
    Hakupalvelu,
    tarjontaMock,
    hakuMock,
    koodistoMock,
    suoritusMock,
    valintaTulosMock,
    valintaRekisteri,
    valintaperusteetMock,
    Timeout(1.minute)
  )

  override def beforeEach() {
    super.beforeEach()
    reset(endPoint)
  }

  test("should not return results if user not in hakukohde organization hierarchy") {
    when(endPoint.request(forPattern(".*applications/byPersonOid.*")))
      .thenReturn((200, List(), getJson("applicationsByPersonOid")))
    when(endPoint.request(forPattern(".*/lomake-editori/api/external/suoritusrekisteri")))
      .thenReturn((200, List(), "{\"applications\": []}"))

    val hakijat = Await.result(
      service.getKkHakijat(
        KkHakijaQuery(
          Some("1.2.246.562.24.81468276424"),
          None,
          None,
          None,
          None,
          Hakuehto.Kaikki,
          1,
          Some(testUser("test", "1.1"))
        ),
        1
      ),
      15.seconds
    )

    hakijat.size should be(0)
  }

  test("should return ataru hakijas") {
    when(endPoint.request(forPattern(".*applications/byPersonOid.*")))
      .thenReturn((200, List(), "{}"))
    when(endPoint.request(forPattern(".*/lomake-editori/api/external/suoritusrekisteri")))
      .thenReturn((200, List(), getJson("ataruApplications")))

    val hakijat = Await.result(
      service.getKkHakijat(
        KkHakijaQuery(
          Some("1.2.246.562.24.91842462815"),
          None,
          None,
          None,
          Some("ryhma"),
          Hakuehto.Kaikki,
          1,
          Some(testUser("test", "1.2.246.562.10.00000000001"))
        ),
        1
      ),
      15.seconds
    )

    hakijat.size should be(2)
    hakijat.last.hakemukset.head.hKelpoisuusMaksuvelvollisuus.get should be("REQUIRED")
    hakijat.last.hakemukset.head.hKelpoisuus should be("ELIGIBLE")
    hakijat.last.hakemukset.head.pohjakoulutus should contain("kk")
  }

  test("should return five hakijas") {
    when(endPoint.request(forPattern(".*listfull.*")))
      .thenReturn((200, List(), getJson("byApplicationOption")))
    when(endPoint.request(forPattern(".*/lomake-editori/api/external/suoritusrekisteri")))
      .thenReturn((200, List(), "{\"applications\": []}"))

    val hakijat = Await.result(
      service.getKkHakijat(
        KkHakijaQuery(
          None,
          None,
          None,
          Some("1.2.246.562.20.649956391810"),
          None,
          Hakuehto.Kaikki,
          1,
          Some(testUser("test", "1.2.246.562.10.00000000001"))
        ),
        1
      ),
      150.seconds
    )

    hakijat.size should be(5)
  }

  test("should return one hyvaksytty hakija") {
    when(endPoint.request(forPattern(".*listfull.*")))
      .thenReturn((200, List(), getJson("byApplicationOption")))
    when(endPoint.request(forPattern(".*/lomake-editori/api/external/suoritusrekisteri")))
      .thenReturn((200, List(), "{\"applications\": []}"))

    val hakijat = Await.result(
      service.getKkHakijat(
        KkHakijaQuery(
          None,
          None,
          None,
          Some("1.11.2"),
          None,
          Hakuehto.Hyvaksytyt,
          1,
          Some(testUser("test", "1.2.246.562.10.00000000001"))
        ),
        1
      ),
      15.seconds
    )

    hakijat.size should be(1)
  }

  test("should convert ilmoittautumiset into sequence in syksyn haku") {
    val hakukohteenKoulutukset: HakukohteenKoulutukset =
      HakukohteenKoulutukset("1.5.1", Some("joku tunniste"), Seq(koulutus1))

    val sijoitteluTulos = SijoitteluTulos(
      "1.2.3",
      Map(("", "1.5.1") -> BigDecimal(4.0)),
      Map(("", "1.5.1") -> Valintatila.KESKEN),
      Map(("", "1.5.1") -> Vastaanottotila.KESKEN),
      Map(("", "1.5.1") -> Ilmoittautumistila.LASNA_KOKO_LUKUVUOSI),
      Map.empty
    )
    val ilmoittautumiset: Seq[Lasnaolo] = Await.result(
      KkHakijaUtil.getLasnaolot(sijoitteluTulos, "1.5.1", "", hakukohteenKoulutukset.koulutukset),
      15.seconds
    )

    ilmoittautumiset should (contain(Lasna(Syksy(2015))) and contain(Lasna(Kevat(2015))))
  }

  test("should convert ilmoittautumiset into sequence in kevään haku") {
    val hakukohteenKoulutukset: HakukohteenKoulutukset =
      HakukohteenKoulutukset("1.5.1", Some("joku tunniste"), Seq(koulutus2))

    val sijoitteluTulos = SijoitteluTulos(
      "1.2.3",
      Map(("", "1.5.1") -> BigDecimal(4.0)),
      Map(("", "1.5.1") -> Valintatila.KESKEN),
      Map(("", "1.5.1") -> Vastaanottotila.KESKEN),
      Map(("", "1.5.1") -> Ilmoittautumistila.LASNA_SYKSY),
      Map.empty
    )
    val ilmoittautumiset = Await.result(
      KkHakijaUtil.getLasnaolot(sijoitteluTulos, "1.5.1", "", hakukohteenKoulutukset.koulutukset),
      15.seconds
    )

    ilmoittautumiset should (contain(Lasna(Syksy(2015))) and contain(Poissa(Kevat(2016))))
  }

  test(
    "should convert ilmoittautumiset into sequence in syksy haku but koulutus start season in next year syksy"
  ) {
    val koulutusSyksy =
      Hakukohteenkoulutus(
        "1.5.6",
        "123456",
        Some("AABB5tga"),
        Some(kausiKoodiS),
        Some(2016),
        None,
        Koulutusohjelma(Map.empty)
      )
    val hakukohteenKoulutukset: HakukohteenKoulutukset =
      HakukohteenKoulutukset("1.5.1", Some("joku tunniste"), Seq(koulutusSyksy))

    val sijoitteluTulos = SijoitteluTulos(
      "1.2.3",
      Map(("", "1.5.1") -> BigDecimal(4.0)),
      Map(("", "1.5.1") -> Valintatila.KESKEN),
      Map(("", "1.5.1") -> Vastaanottotila.KESKEN),
      Map(("", "1.5.1") -> Ilmoittautumistila.LASNA_KOKO_LUKUVUOSI),
      Map.empty
    )
    val ilmoittautumiset = Await.result(
      KkHakijaUtil.getLasnaolot(sijoitteluTulos, "1.5.1", "", hakukohteenKoulutukset.koulutukset),
      15.seconds
    )

    ilmoittautumiset should (contain(Lasna(Syksy(2016))) and contain(Lasna(Kevat(2017))))
  }

  test("should show turvakielto true from hakemus") {
    when(endPoint.request(forPattern(".*applications/byPersonOid.*")))
      .thenReturn((200, List(), getJson("applicationsByPersonOid")))
    when(endPoint.request(forPattern(".*/lomake-editori/api/external/suoritusrekisteri")))
      .thenReturn((200, List(), "{\"applications\": []}"))

    val hakijat = Await.result(
      service.getKkHakijat(
        KkHakijaQuery(
          Some("1.2.246.562.24.81468276424"),
          None,
          None,
          None,
          None,
          Hakuehto.Kaikki,
          1,
          Some(testUser("test", "1.2.246.562.10.00000000001"))
        ),
        1
      ),
      15.seconds
    )

    hakijat.head.turvakielto should be(true)
  }

  test("should return turvakielto false from hakemus") {
    when(endPoint.request(forPattern(".*applications/byPersonOid.*")))
      .thenReturn((200, List(), getJson("applicationsByPersonOid")))
    when(endPoint.request(forPattern(".*/lomake-editori/api/external/suoritusrekisteri")))
      .thenReturn((200, List(), "{\"applications\": []}"))

    val hakijat = Await.result(
      service.getKkHakijat(
        KkHakijaQuery(
          Some("1.2.246.562.24.81468276424"),
          None,
          None,
          None,
          None,
          Hakuehto.Kaikki,
          1,
          Some(testUser("test", "1.2.246.562.10.00000000001"))
        ),
        1
      ),
      15.seconds
    )

    hakijat(1).turvakielto should be(false)
  }

  test("should return empty hakukelpoisuus by default") {
    when(endPoint.request(forPattern(".*applications/byPersonOid.*")))
      .thenReturn((200, List(), getJson("applicationsByPersonOid")))
    when(endPoint.request(forPattern(".*/lomake-editori/api/external/suoritusrekisteri")))
      .thenReturn((200, List(), "{\"applications\": []}"))

    val hakijat = Await.result(
      service.getKkHakijat(
        KkHakijaQuery(
          Some("1.2.246.562.24.81468276424"),
          None,
          None,
          None,
          None,
          Hakuehto.Kaikki,
          1,
          Some(testUser("test", "1.2.246.562.10.00000000001"))
        ),
        1
      ),
      15.seconds
    )

    hakijat.head.hakemukset.exists(_.hKelpoisuus == "") should be(true)
  }

  test("should return hakukelpoisuus from hakemus") {
    when(endPoint.request(forPattern(".*applications/byPersonOid.*")))
      .thenReturn((200, List(), getJson("applicationsByPersonOid")))
    when(endPoint.request(forPattern(".*/lomake-editori/api/external/suoritusrekisteri")))
      .thenReturn((200, List(), "{\"applications\": []}"))

    val hakijat = Await.result(
      service.getKkHakijat(
        KkHakijaQuery(
          Some("1.2.246.562.24.81468276424"),
          None,
          None,
          None,
          None,
          Hakuehto.Kaikki,
          1,
          Some(testUser("test", "1.2.246.562.10.00000000001"))
        ),
        1
      ),
      15.seconds
    )

    hakijat.head.hakemukset.exists(_.hKelpoisuus == "NOT_CHECKED") should be(true)
  }

  test("should return kotikunta default if it is not defined in hakemus") {
    when(endPoint.request(forPattern(".*applications/byPersonOid.*")))
      .thenReturn((200, List(), getJson("applicationsByPersonOid")))
    when(endPoint.request(forPattern(".*/lomake-editori/api/external/suoritusrekisteri")))
      .thenReturn((200, List(), "{\"applications\": []}"))

    val hakijat = Await.result(
      service.getKkHakijat(
        KkHakijaQuery(
          Some("1.2.246.562.24.81468276424"),
          None,
          None,
          None,
          None,
          Hakuehto.Kaikki,
          1,
          Some(testUser("test", "1.2.246.562.10.00000000001"))
        ),
        1
      ),
      15.seconds
    )

    hakijat.head.kotikunta should be("999")
  }

  test("should return kotikunta from hakemus") {
    when(endPoint.request(forPattern(".*applications/byPersonOid.*")))
      .thenReturn((200, List(), getJson("applicationsByPersonOid")))
    when(endPoint.request(forPattern(".*/lomake-editori/api/external/suoritusrekisteri")))
      .thenReturn((200, List(), "{\"applications\": []}"))

    val hakijat = Await.result(
      service.getKkHakijat(
        KkHakijaQuery(
          Some("1.2.246.562.24.81468276424"),
          None,
          None,
          None,
          None,
          Hakuehto.Kaikki,
          1,
          Some(testUser("test", "1.2.246.562.10.00000000001"))
        ),
        1
      ),
      15.seconds
    )

    hakijat(1).kotikunta should be("049")
  }

  test("should return postitoimipaikka") {
    when(endPoint.request(forPattern(".*applications/byPersonOid.*")))
      .thenReturn((200, List(), getJson("applicationsByPersonOid")))
    when(endPoint.request(forPattern(".*/lomake-editori/api/external/suoritusrekisteri")))
      .thenReturn((200, List(), "{\"applications\": []}"))

    val hakijat = Await.result(
      service.getKkHakijat(
        KkHakijaQuery(
          Some("1.2.246.562.24.81468276424"),
          None,
          None,
          None,
          None,
          Hakuehto.Kaikki,
          1,
          Some(testUser("test", "1.2.246.562.10.00000000001"))
        ),
        1
      ),
      15.seconds
    )

    hakijat.head.postitoimipaikka should be("Posti_02140")
  }

  test(
    "should not return koulutuksenAlkamiskausi, koulutuksenAlkamisvuosi, koulutuksenAlkamisPvms"
  ) {
    when(endPoint.request(forPattern(".*applications/byPersonOid.*")))
      .thenReturn((200, List(), getJson("applicationsByPersonOid")))
    when(endPoint.request(forPattern(".*/lomake-editori/api/external/suoritusrekisteri")))
      .thenReturn((200, List(), "{\"applications\": []}"))

    val hakijat = Await.result(
      service.getKkHakijat(
        KkHakijaQuery(
          Some("1.2.246.562.24.81468276424"),
          None,
          None,
          None,
          None,
          Hakuehto.Kaikki,
          1,
          Some(testUser("test", "1.2.246.562.10.00000000001"))
        ),
        1
      ),
      15.seconds
    )

    val koulutus: Hakukohteenkoulutus = hakijat.head.hakemukset.head.hakukohteenKoulutukset.head
    koulutus.koulutuksenAlkamiskausi should be(None)
    koulutus.koulutuksenAlkamisvuosi should be(None)
    koulutus.koulutuksenAlkamisPvms should be(None)
  }

  test("should not return hakemus of expired haku") {
    when(endPoint.request(forPattern(".*applications/byPersonOid.*")))
      .thenReturn((200, List(), getJson("applicationsByPersonOid")))
    when(endPoint.request(forPattern(".*/lomake-editori/api/external/suoritusrekisteri")))
      .thenReturn((200, List(), "{\"applications\": []}"))

    val hakijat = Await.result(
      service.getKkHakijat(
        KkHakijaQuery(
          Some("1.2.246.562.24.81468276424"),
          None,
          None,
          None,
          None,
          Hakuehto.Kaikki,
          1,
          Some(testUser("test", "1.2.246.562.10.00000000001"))
        ),
        1
      ),
      15.seconds
    )

    hakijat.size should be(2)
  }

  test("should not have FI as default aidinkieli, asiointikieli or koulusivistyskieli") {
    when(endPoint.request(forPattern(".*applications/byPersonOid.*")))
      .thenReturn((200, List(), getJson("applicationsByPersonOid")))
    when(endPoint.request(forPattern(".*/lomake-editori/api/external/suoritusrekisteri")))
      .thenReturn((200, List(), "{\"applications\": []}"))

    val hakijat = Await.result(
      service.getKkHakijat(
        KkHakijaQuery(
          Some("1.2.246.562.24.81468276424"),
          None,
          None,
          None,
          None,
          Hakuehto.Kaikki,
          1,
          Some(testUser("test", "1.2.246.562.10.00000000001"))
        ),
        1
      ),
      15.seconds
    )

    hakijat.last.aidinkieli should be("99")
    hakijat.last.asiointikieli should be("9") // Default is not empty!
    hakijat.last.koulusivistyskieli should be("99")
  }

  def testAsiointikieliTakenFromAtaruHakemuksetAndNeverFromHenkilo(apiVersion: Int): Assertion = {
    val serviceThatShouldTakeAsiointikieliFromHakemus = new KkHakijaService(
      hakemusService,
      Hakupalvelu,
      tarjontaMock,
      hakuMock,
      koodistoMock,
      suoritusMock,
      valintaTulosMock,
      valintaRekisteri,
      valintaperusteetMock,
      Timeout(1.minute)
    )
    when(endPoint.request(forPattern(".*applications/byPersonOid.*")))
      .thenReturn((200, List(), "{}"))
    when(endPoint.request(forPattern(".*/lomake-editori/api/external/suoritusrekisteri")))
      .thenReturn((200, List(), getJson("ataruApplications")))

    val hakijat = Await.result(
      serviceThatShouldTakeAsiointikieliFromHakemus.getKkHakijat(
        KkHakijaQuery(
          Some("1.2.246.562.24.91842462815"),
          None,
          None,
          None,
          Some("ryhma"),
          Hakuehto.Kaikki,
          1,
          Some(testUser("test", "1.2.246.562.10.00000000001"))
        ),
        version = apiVersion
      ),
      15.seconds
    )

    hakijat.size should be(2)
    val finnish = "1"
    val swedishAsInMockOnr = "2"
    val english = "3"
    val default = "9"
    hakijat.exists(_.asiointikieli == finnish) should be(true)
    hakijat.exists(_.asiointikieli == english) should be(true)
    hakijat.exists(_.asiointikieli == default) should be(false)
    hakijat.exists(_.asiointikieli == swedishAsInMockOnr) should be(false)
  }

  test("v2 should get asiointikieli from ataru hakemus")(testFun =
    testAsiointikieliTakenFromAtaruHakemuksetAndNeverFromHenkilo(2)
  )

  test("v3 should get asiointikieli from ataru hakemus")(testFun =
    testAsiointikieliTakenFromAtaruHakemuksetAndNeverFromHenkilo(3)
  )

  test("should return default kansalaisuus, asuinmaa, kotikunta") {
    when(endPoint.request(forPattern(".*applications/byPersonOid.*")))
      .thenReturn((200, List(), getJson("applicationsByPersonOid")))
    when(endPoint.request(forPattern(".*/lomake-editori/api/external/suoritusrekisteri")))
      .thenReturn((200, List(), "{\"applications\": []}"))

    val hakijat = Await.result(
      service.getKkHakijat(
        KkHakijaQuery(
          Some("1.2.246.562.24.81468276424"),
          None,
          None,
          None,
          None,
          Hakuehto.Kaikki,
          1,
          Some(testUser("test", "1.2.246.562.10.00000000001"))
        ),
        1
      ),
      15.seconds
    )

    hakijat.last.kansalaisuus should be(Some("999"))
    hakijat.last.maa should be("999")
    hakijat.head.kotikunta should be("999")
  }

  test("v2 call to service should return lukuvuosimaksu by hakukohde") {
    when(endPoint.request(forPattern(".*applications/byPersonOid.*")))
      .thenReturn((200, List(), getJson("applicationByPersonOidWithMaksuvelvollisuus")))
    when(endPoint.request(forPattern(".*/lomake-editori/api/external/suoritusrekisteri")))
      .thenReturn((200, List(), "{\"applications\": []}"))

    val hakijat: Seq[Hakija] = Await.result(
      service.getKkHakijat(
        KkHakijaQuery(
          Some(personOidWithLukuvuosimaksu),
          None,
          None,
          None,
          None,
          Hakuehto.Kaikki,
          1,
          Some(testUser("test", "1.2.246.562.10.00000000001"))
        ),
        2
      ),
      15.seconds
    )
    val lukuvuosimaksuString = hakijat
      .map(
        _.hakemukset.map(hakemus => s"hakukohde ${hakemus.hakukohde}: ${hakemus.lukuvuosimaksu}")
      )
      .mkString(",")
    logger.debug(s"When testing lukuvuosimaksus, got hakijat response: $hakijat")
    logger.debug(s"When testing lukuvuosimaksus, got lukuvuosimaksus: $lukuvuosimaksuString")

    hakijat should have size 1
    val hakijaWithMaksu = hakijat.head
    val hakemuksetByHakukohdeOid = hakijaWithMaksu.hakemukset.groupBy(_.hakukohde)
    hakemuksetByHakukohdeOid should have size 3

    val lukuvuosiMaksuForPaymentRequiredHakukohde = hakemuksetByHakukohdeOid
      .get(paymentRequiredHakukohdeWithMaksettu)
      .flatMap(_.head.lukuvuosimaksu)
    val lukuvuosimaksuForNotPaidHakukohde = hakemuksetByHakukohdeOid
      .get(paymentRquiredHakukohdeWithoutPayment)
      .flatMap(_.head.lukuvuosimaksu)
    val lukuvuosimaksuForNoPaymentRequiredButAnywayPaidHakukohde = hakemuksetByHakukohdeOid
      .get(noPaymentRequiredHakukohdeButMaksettu)
      .flatMap(_.head.lukuvuosimaksu)

    lukuvuosiMaksuForPaymentRequiredHakukohde should be(Some(Maksuntila.maksettu.toString))
    lukuvuosimaksuForNotPaidHakukohde should be(Some(Maksuntila.maksamatta.toString))
    lukuvuosimaksuForNoPaymentRequiredButAnywayPaidHakukohde should be(
      Some(Maksuntila.maksettu.toString)
    )
  }

  def testIncludeHakemuksetForAllHakijaAliasesAndSetToMasterOid(apiVersion: Int): Assertion = {
    when(endPoint.request(forPattern(".*applications/byPersonOid.*")))
      .thenReturn((200, List(), getJson("applicationsByPersonOidWithAliases")))
    when(endPoint.request(forPattern(".*/lomake-editori/api/external/suoritusrekisteri")))
      .thenReturn((200, List(), "{\"applications\": []}"))
    val hakijaWithAliases = MockOppijaNumeroRekisteri.henkiloOid

    val hakijat = Await.result(
      service.getKkHakijat(
        KkHakijaQuery(
          Some(hakijaWithAliases),
          None,
          None,
          None,
          None,
          Hakuehto.Kaikki,
          1,
          Some(testUser("test", "1.2.246.562.10.00000000001"))
        ),
        version = apiVersion
      ),
      15.seconds
    )

    hakijat.size should be(3)
    hakijat.forall(_.oppijanumero == MockOppijaNumeroRekisteri.masterOid) should be(true)
  }

  test("v4 Include hakemukset for duplicate henkilöoid's (alias)") {
    testIncludeHakemuksetForAllHakijaAliasesAndSetToMasterOid(4)
  }

  test("v3 Include hakemukset for duplicate henkilöoid's (alias)") {
    testIncludeHakemuksetForAllHakijaAliasesAndSetToMasterOid(3)
  }

  test("v2 Include hakemukset for duplicate henkilöoid's (alias)") {
    testIncludeHakemuksetForAllHakijaAliasesAndSetToMasterOid(2)
  }

  def testUser(user: String, organisaatioOid: String) = new User {
    override val username: String = user
    override val auditSession = AuditSessionRequest(user, Set(organisaatioOid), "", "")
    override def orgsFor(action: String, resource: String): Set[String] = Set(organisaatioOid)
    override def casAuthenticationToken: CasAuthenticationToken =
      fi.vm.sade.hakurekisteri.web.rest.support.TestUser.casAuthenticationToken
  }

  def seq2journal(s: Seq[FullHakemus]) = {
    val journal = new InMemJournal[FullHakemus, String]
    s.foreach((h: FullHakemus) => {
      journal.addModification(Updated[FullHakemus, String](h.identify(h.oid)))
    })
    journal
  }

  override def stop(): Unit = {
    Await.result(system.terminate(), 15.seconds)
    super.stop()
  }
}
