package fi.vm.sade.hakurekisteri.integration.hakemus

import akka.actor.{ActorSystem, Props}
import fi.vm.sade.hakurekisteri.{Config, DefaultConfig, MockCacheFactory, MockConfig}
import fi.vm.sade.hakurekisteri.acceptance.tools.HakeneetSupport
import fi.vm.sade.hakurekisteri.integration._
import fi.vm.sade.hakurekisteri.integration.hakukohde.HakukohdeAggregatorActorRef
import fi.vm.sade.hakurekisteri.integration.henkilo.{
  MockOppijaNumeroRekisteri,
  PersonOidsWithAliases
}
import fi.vm.sade.hakurekisteri.integration.koodisto.KoodistoActorRef
import fi.vm.sade.hakurekisteri.integration.kouta.KoutaInternalActorRef
import fi.vm.sade.hakurekisteri.integration.organisaatio.OrganisaatioActorRef
import fi.vm.sade.hakurekisteri.integration.tarjonta.TarjontaActorRef
import org.joda.time.LocalDate
import org.mockito.Mockito._
import org.scalatest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.concurrent.Await
import scala.concurrent.duration._

class HakemusServiceSpec
    extends AnyFlatSpec
    with Matchers
    with MockitoSugar
    with DispatchSupport
    with LocalhostProperties
    with HakeneetSupport {

  val endPoint = mock[Endpoint]
  val hakuappClient = new VirkailijaRestClient(
    ServiceConfig(serviceUrl = "http://localhost/haku-app"),
    aClient = Some(new CapturingAsyncHttpClient(endPoint))
  )
  val ataruClient = new VirkailijaRestClient(
    ServiceConfig(serviceUrl = "http://localhost/lomake-editori"),
    aClient = Some(new CapturingAsyncHttpClient(endPoint))
  )
  val hakukohdeAggregatorMock = new HakukohdeAggregatorActorRef(
    system.actorOf(Props(new MockedHakukohdeAggregatorActor()))
  )
  val koutaInternalMock = new KoutaInternalActorRef(
    system.actorOf(Props(new MockedKoutaInternalActor()))
  )
  val organisaatioMock: OrganisaatioActorRef = new OrganisaatioActorRef(
    system.actorOf(Props(new MockedOrganisaatioActor()))
  )
  val koodistoMock: KoodistoActorRef = new KoodistoActorRef(
    system.actorOf(Props(new MockedKoodistoActor()))
  )
  val hakemusService = new HakemusService(
    hakuappClient,
    ataruClient,
    hakukohdeAggregatorMock,
    koutaInternalMock,
    organisaatioMock,
    MockOppijaNumeroRekisteri,
    koodistoMock,
    Config.mockDevConfig,
    MockCacheFactory.get(),
    pageSize = 10
  )

  behavior of "hakemuksetForPerson"

  it should "return applications by person oid" in {
    when(endPoint.request(forPattern(".*applications/byPersonOid.*")))
      .thenReturn((200, List(), getJson("applicationsByPersonOid")))
    when(endPoint.request(forPattern(".*/lomake-editori/api/external/suoritusrekisteri")))
      .thenReturn((200, List(), "{\"applications\": []}"))

    Await
      .result(hakemusService.hakemuksetForPerson("1.2.246.562.24.81468276424"), 10.seconds)
      .size should be(2)
  }

  it should "return applications from ataru by person oid" in {
    when(endPoint.request(forPattern(".*applications/byPersonOid.*")))
      .thenReturn((200, List(), "{}"))
    when(endPoint.request(forPattern(".*/lomake-editori/api/external/suoritusrekisteri")))
      .thenReturn((200, List(), getJson("ataruApplications")))
    val expectedPersonOid = "1.2.246.562.24.91842462815"

    val hakemukset = Await.result(hakemusService.hakemuksetForPerson(expectedPersonOid), 10.seconds)

    hakemukset.size should be(2)
    hakemukset.forall(_.personOid == Some(expectedPersonOid)) should be(true)
  }

  it should "return applications applications that were submitted between january and august this year" in {

    when(endPoint.request(forPattern(".*applications/byPersonOid.*")))
      .thenReturn((200, List(), "{}"))

    val currentYear = new LocalDate(System.currentTimeMillis()).getYear
    when(endPoint.request(forPattern(".*/lomake-editori/api/external/suoritusrekisteri")))
      .thenReturn(
        (
          200,
          List(),
          getJsonWithReplaces(
            "ataruApplicationsJatkuva",
            Map("CURRENTYEAR" -> currentYear.toString, "LASTYEAR" -> (currentYear - 1).toString)
          )
        )
      )

    val personOids =
      Await.result(hakemusService.springPersonOidsForJatkuvaHaku("1.2.3"), 10.seconds)
    personOids.size should be(1)
    personOids.contains("1.2.246.562.24.91842462815") should be(true)
  }

  behavior of "enrichAtaruHakemukset"

  it should "use asiointiKieli from ataru hakemus (and NOT from person from ONR)" in {
    val asiointiKieliFromOnr = "fi"
    val asiointiKieliFromHakemus = "en"
    val personOid = "1.2.3.4.5.6"
    val ataruHenkilo = henkilo.Henkilo(
      "ataruHenkiloOid",
      Some("ataruHetu"),
      Some(List("ataruHetu")),
      None,
      None,
      None,
      None,
      List(),
      None,
      None,
      turvakielto = Some(false)
    )
    val ataruHakemusDto = AtaruHakemusDto(
      "ataruOid",
      personOid,
      "",
      "",
      "",
      kieli = asiointiKieliFromHakemus,
      List(),
      "",
      "",
      "",
      "",
      None,
      None,
      "",
      false,
      false,
      Map(),
      Map(),
      Map(),
      List(),
      List(),
      None
    )

    val ataruHakemukset: List[AtaruHakemus] =
      Await.result(
        hakemusService.enrichAtaruHakemukset(List(ataruHakemusDto), Map(personOid -> ataruHenkilo)),
        10.seconds
      )

    ataruHakemukset.size should be(1)
    ataruHakemukset(0).asiointiKieli should be(asiointiKieliFromHakemus)
  }

  it should "index hakutoive preference numbers starting from 1" in {
    val asiointiKieliFromOnr = "fi"
    val asiointiKieliFromHakemus = "en"
    val personOid = "1.2.3.4.5.6"
    val ataruHenkilo = henkilo.Henkilo(
      "ataruHenkiloOid",
      Some("ataruHetu"),
      Some(List("ataruHetu")),
      None,
      None,
      None,
      None,
      List(),
      None,
      None,
      turvakielto = Some(false)
    )
    val ataruHakemusDto = AtaruHakemusDto(
      "ataruOid",
      personOid,
      "",
      "",
      "",
      kieli = asiointiKieliFromHakemus,
      List("1.2.246.562.20.666", "1.2.246.562.20.667"),
      "",
      "",
      "",
      "",
      None,
      None,
      "",
      false,
      false,
      Map(),
      Map(),
      Map(),
      List(),
      List(),
      None
    )

    val ataruHakemukset: List[AtaruHakemus] =
      Await.result(
        hakemusService
          .enrichAtaruHakemukset(List(ataruHakemusDto), Map(personOid -> ataruHenkilo), true),
        10.seconds
      )

    ataruHakemukset.size should be(1)
    val hakutoiveet = ataruHakemukset.head.hakutoiveet.get
    hakutoiveet.size should be(2)
    hakutoiveet.head.preferenceNumber should be(1)
    hakutoiveet(1).preferenceNumber should be(2)

  }

  behavior of "hakemusForPersonsInHaku"

  it should "return applications when searching with both persons and application system" in {
    when(endPoint.request(forPattern(".*applications/byPersonOid.*")))
      .thenReturn((200, List(), getJson("applicationsByPersonOidsAndHaku")))
    when(endPoint.request(forPattern(".*/lomake-editori/api/external/suoritusrekisteri")))
      .thenReturn((200, List(), "{\"applications\": []}"))

    val persons = Set("1.2.246.562.24.62737906266", "1.2.246.562.24.99844104050")
    val applicationSystem = "1.2.246.562.29.90697286251"
    val res = Await.result(
      hakemusService.hakemuksetForPersonsInHaku(persons, applicationSystem),
      10.seconds
    )
    res.size should be(2)

    res.foreach(application => {
      application.applicationSystemId should be(applicationSystem)
      persons.contains(application.personOid.get) should be(true)
    })
  }

  behavior of "hakemusForHakukohde"

  it should "return applications by application option oid" in {
    when(endPoint.request(forPattern(".*listfull.*")))
      .thenReturn((200, List(), getJson("byApplicationOption")))
    when(endPoint.request(forPattern(".*/lomake-editori/api/external/suoritusrekisteri")))
      .thenReturn((200, List(), "{\"applications\": []}"))

    Await
      .result(
        hakemusService.hakemuksetForHakukohde("1.2.246.562.20.649956391810", None),
        10.seconds
      )
      .size should be(6)
  }

  it should "support haku-app pagination" in {
    when(endPoint.request(forPattern(".*listfull.*start=0.*")))
      .thenReturn((200, List(), getJson("listfull-0")))
    when(endPoint.request(forPattern(".*listfull.*start=10.*")))
      .thenReturn((200, List(), getJson("listfull-1")))
    when(endPoint.request(forPattern(".*listfull.*start=20.*")))
      .thenReturn((200, List(), "[]"))
    when(endPoint.request(forPattern(".*/lomake-editori/api/external/suoritusrekisteri")))
      .thenReturn((200, List(), "{\"applications\": []}"))

    Await
      .result(
        hakemusService.hakemuksetForHakukohde("1.2.246.562.20.649956391810", None),
        10.seconds
      )
      .size should be(20)
  }

  "processModifiedHakemukset" should "execute trigger function for modified applications" in {
    val system = ActorSystem("hakurekisteri")
    implicit val scheduler = system.scheduler

    when(endPoint.request(forPattern(".*listfull.*start=0.*")))
      .thenReturn((200, List(), getJson("listfull-0")))
      .thenReturn((200, List(), "[]"))
    when(endPoint.request(forPattern(".*listfull.*start=1.*")))
      .thenReturn((200, List(), getJson("listfull-1")))
    when(endPoint.request(forPattern(".*listfull.*start=2.*")))
      .thenReturn((200, List(), "[]"))
    when(endPoint.request(forPattern(".*/lomake-editori/api/external/suoritusrekisteri")))
      .thenReturn((200, List(), getJson("ataruApplications")))

    var triggerCounter = 0
    val trigger =
      Trigger(f = (hakemus: HakijaHakemus, personOidsWithAliases: PersonOidsWithAliases) => {
        triggerCounter += 1
      })

    hakemusService.addTrigger(trigger)
    hakemusService.addTrigger(trigger)

    hakemusService.processModifiedHakemukset(refreshFrequency = 1.second)

    Thread.sleep(2000)

    triggerCounter should be(44)
  }

  it should "be able to skip application without person oid" in {
    var triggerCounter = 0
    val trigger = Trigger(f =
      (oid: String, hetu: String, hakuOid: String, personOidsWithAliases: PersonOidsWithAliases) =>
        {
          triggerCounter += 1
        }
    )
    val answers = Some(
      HakemusAnswers(henkilotiedot =
        Some(HakemusHenkilotiedot(Henkilotunnus = Some("123456-7890")))
      )
    )
    val ataruHenkilo = henkilo.Henkilo(
      "ataruHenkiloOid",
      Some("ataruHetu"),
      Some(List("ataruHetu")),
      None,
      None,
      None,
      None,
      List(),
      None,
      None,
      turvakielto = Some(false)
    )

    trigger.f(
      FullHakemus(
        "oid",
        Some("hakijaOid"),
        "hakuOid",
        answers,
        None,
        Nil,
        Nil,
        Some(1615219923688L),
        updated = None
      ),
      PersonOidsWithAliases(Set("oid"), Map("oid" -> Set("oid")))
    )
    trigger.f(
      AtaruHakemus(
        "ataruOid",
        Some("ataruHakijaOid"),
        "hakuOid",
        "",
        "",
        None,
        ataruHenkilo,
        "fi",
        "email",
        "matkapuhelin",
        "lahiosoite",
        "postinumero",
        Some("postitoimipaikka"),
        Some("kotikunta"),
        "asuinmaa",
        true,
        true,
        Map.empty,
        Map.empty,
        Map.empty,
        List.empty,
        List.empty,
        None
      ),
      PersonOidsWithAliases(Set("oid"), Map("oid" -> Set("oid")))
    )
    triggerCounter should equal(2)
    trigger.f(
      FullHakemus("oid", None, "hakuOid", answers, None, Nil, Nil, Some(1615219923688L), None),
      PersonOidsWithAliases(Set("oid"), Map("oid" -> Set("oid")))
    )
    trigger.f(
      AtaruHakemus(
        "ataruOid",
        None,
        "hakuOid",
        "",
        "",
        None,
        ataruHenkilo,
        "en",
        "email",
        "matkapuhelin",
        "lahiosoite",
        "postinumero",
        Some("postitoimipaikka"),
        Some("kotikunta"),
        "asuinmaa",
        true,
        true,
        Map.empty,
        Map.empty,
        Map.empty,
        List.empty,
        List.empty,
        None
      ),
      PersonOidsWithAliases(Set("oid"), Map("oid" -> Set("oid")))
    )
    triggerCounter should equal(2)
  }

  "hetuAndPersonOidForHaku" should "return hetus and personOids" in {
    when(endPoint.request(forPattern(".*/lomake-editori/api/external/suoritusrekisteri")))
      .thenReturn((200, List(), getJson("ataruApplications")))

    val result: Seq[HetuPersonOid] =
      Await.result(hakemusService.hetuAndPersonOidForHaku("testHaku"), 10.seconds)
    result.length should equal(2)
  }

}
