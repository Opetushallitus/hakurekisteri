package fi.vm.sade.hakurekisteri.ovara

import fi.vm.sade.hakurekisteri.integration.haku.{AllHaut, Haku, HakuRequest, Kieliversiot}
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.testkit.TestActorRef
import fi.vm.sade.hakurekisteri.dates.{Ajanjakso, InFuture}
import fi.vm.sade.hakurekisteri.{Config, MockConfig}
import fi.vm.sade.hakurekisteri.ensikertalainen.EnsikertalainenActor
import fi.vm.sade.hakurekisteri.integration.hakemus.{
  AtaruHakemuksenHenkilotiedot,
  AtaruHenkiloSearchParams,
  HakemuksenHarkinnanvaraisuus,
  HakemusService,
  HakemusServiceMock
}
import fi.vm.sade.hakurekisteri.integration.henkilo.OppijaNumeroRekisteri
import fi.vm.sade.hakurekisteri.integration.kooste.KoosteServiceMock
import fi.vm.sade.hakurekisteri.integration.tarjonta.TarjontaActorRef
import fi.vm.sade.hakurekisteri.integration.valintarekisteri.ValintarekisteriActorRef
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver
import org.joda.time.DateTime
import org.scalatest.mockito.MockitoSugar.mock
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import support.DbJournals

import scala.concurrent.duration._
import scala.language.implicitConversions
import scala.concurrent.{Await, Future}

class OvaraServiceSpec extends FlatSpec with Matchers with BeforeAndAfterAll {

  private implicit var database: HakurekisteriDriver.backend.Database = _
  private implicit val system = ActorSystem("test-ovara")

  private val config: MockConfig = new MockConfig

  val client = new MockSiirtotiedostoClient

  var ovaraService: OvaraService = _

  val kkHakuOid = "1.2.3.4.44444"
  val toinenAsteHakuOid = "1.2.3.4.55555"

  val hakemusServiceMocked = new HakemusServiceMock {
    override def ataruhakemustenHenkilot(
      params: AtaruHenkiloSearchParams
    ): Future[List[AtaruHakemuksenHenkilotiedot]] = {
      params.hakuOid.getOrElse("1.2.3.66666") match {
        case oid if oid == kkHakuOid =>
          Future.successful(
            Range(1, 201)
              .map(i => AtaruHakemuksenHenkilotiedot("1.2.3.11." + i, Some("1.2.3.24." + i), None))
              .toList
          )
        case oid if oid == toinenAsteHakuOid =>
          Future.successful(
            Range(1, 201)
              .map(i => AtaruHakemuksenHenkilotiedot("1.2.3.11." + i, Some("1.2.3.24." + i), None))
              .toList
          )
        case _ =>
          Future.successful(List[AtaruHakemuksenHenkilotiedot]())
      }
    }
  }

  val koosteServiceMocked = new KoosteServiceMock {
    override def getHarkinnanvaraisuudetForHakemusOidsWithTimeout(
      hakemusOids: Seq[String],
      timeout: Duration
    ): Future[Seq[HakemuksenHarkinnanvaraisuus]] = {
      Future.successful(hakemusOids.map(oid => HakemuksenHarkinnanvaraisuus(oid, None, List.empty)))
    }

    override def getProxysuorituksetForHakemusOids(
      hakuOid: String,
      hakemusOids: Seq[String]
    ): Future[Map[String, Map[String, String]]] = {
      //println(s"Proxysuoritukset!> ${hakemusOids.head.split('.').last}" )
      val result = hakemusOids
        .map(hakemusOid => "1.2.3.24." + hakemusOid.split('.').last -> Map("key" -> "value"))
        .toMap
      println(s"Proxysuoritukset! $result")
      Future.successful(result)
    }
  }

  val hakuActorMock: TestActorRef[MockHakuActor] = TestActorRef[MockHakuActor](
    Props(
      new MockHakuActor()
    )
  )
  val ekActorMock: TestActorRef[EnsikertalainenActor] = TestActorRef[EnsikertalainenActor](
    Props(
      new EnsikertalainenActor(
        mock[ActorRef],
        mock[ActorRef],
        mock[ValintarekisteriActorRef],
        mock[TarjontaActorRef],
        mock[ActorRef],
        mock[HakemusService],
        mock[OppijaNumeroRekisteri],
        mock[Config]
      )
    )
  )

  val someHakuToDoNothingWith = Haku(
    Kieliversiot(Some("haku"), None, None),
    "1.1111",
    Ajanjakso(new DateTime(), InFuture),
    "kausi_s#1",
    2014,
    Some("kausi_k#1"),
    Some(2015),
    false,
    false,
    None,
    None,
    "hakutapa_01#1",
    Some("hakutyyppi_01#1"),
    None
  )

  val kkHaku = Haku(
    Kieliversiot(Some("haku"), None, None),
    kkHakuOid,
    Ajanjakso(new DateTime(), InFuture),
    "kausi_s#1",
    2014,
    Some("kausi_k#1"),
    Some(2015),
    kkHaku = true,
    toisenAsteenHaku = false,
    None,
    None,
    "hakutapa_01#1",
    Some("hakutyyppi_01#1"),
    None
  )

  val toisenAsteenYhteishaku = Haku(
    Kieliversiot(Some("haku"), None, None),
    toinenAsteHakuOid,
    Ajanjakso(new DateTime(), InFuture),
    "kausi_s#1",
    2014,
    Some("kausi_k#1"),
    Some(2015),
    kkHaku = false,
    toisenAsteenHaku = true,
    None,
    None,
    "hakutapa_01#1",
    Some("hakutyyppi_01#1"),
    None
  )

  class MockHakuActor extends Actor {
    override def receive: Receive = {
      case HakuRequest =>
        sender ! AllHaut(Seq(someHakuToDoNothingWith, kkHaku, toisenAsteenYhteishaku))
      case msg: Int => sender ! msg.toString
    }
  }

  override def beforeAll(): Unit = {
    val journals: DbJournals = new DbJournals(config)
    database = journals.database
    ovaraService = new OvaraService(
      new OvaraDbRepositoryImpl(database),
      client,
      ekActorMock,
      hakuActorMock,
      1000,
      hakemusServiceMocked,
      koosteServiceMocked
    )
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    try super.afterAll()
    finally {
      Await.result(system.terminate(), 5.seconds)
      database.close()
    }
  }

  it should "form siirtotiedosto with sliding time windows" in {
    val result = ovaraService.muodostaSeuraavaSiirtotiedosto
    println("result" + result)
    result.windowStart should equal(
      1719401507582L
    ) //Migraatiossa asetettu pohja-arvo, jotta ensimmäinen ajastus ei sisällä kaikkea dataa.
    val result2 = ovaraService.muodostaSeuraavaSiirtotiedosto
    println("result2" + result)
    result2.windowStart should equal(result.windowEnd)
  }

  it should "form harkinnanvaraisuudet" in {
    val result = ovaraService.triggerDailyProcessing(
      DailyProcessingParams(
        "exec-id",
        vainAktiiviset = true,
        harkinnanvaraisuudet = true,
        ensikertalaisuudet = false,
        proxySuoritukset = false
      )
    )
    //println("result" + result)

    result.size should equal(1)
    result.head.total should equal(200)
    result.head.tyyppi should equal("harkinnanvaraisuus")
  }

  it should "form proxysuoritukset" in {
    val result = ovaraService.triggerDailyProcessing(
      DailyProcessingParams(
        "exec-id",
        vainAktiiviset = true,
        harkinnanvaraisuudet = false,
        ensikertalaisuudet = false,
        proxySuoritukset = true
      )
    )
    //println("result" + result)

    result.size should equal(1)
    result.head.total should equal(200)
    result.head.tyyppi should equal("proxysuoritukset")
  }

}
