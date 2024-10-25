package fi.vm.sade.hakurekisteri.ovara

import fi.vm.sade.hakurekisteri.integration.haku.{AllHaut, Haku, HakuRequest, Kieliversiot}
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.testkit.TestActorRef
import fi.vm.sade.hakurekisteri.dates.{Ajanjakso, InFuture}
import fi.vm.sade.hakurekisteri.{Config, MockConfig}
import fi.vm.sade.hakurekisteri.ensikertalainen.EnsikertalainenActor
import fi.vm.sade.hakurekisteri.integration.hakemus.HakemusService
import fi.vm.sade.hakurekisteri.integration.henkilo.OppijaNumeroRekisteri
import fi.vm.sade.hakurekisteri.integration.tarjonta.TarjontaActorRef
import fi.vm.sade.hakurekisteri.integration.valintarekisteri.ValintarekisteriActorRef
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver
import org.joda.time.DateTime
import org.scalatest.mockito.MockitoSugar.mock
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import support.DbJournals

import scala.concurrent.duration._
import scala.language.implicitConversions
import scala.concurrent.Await

class OvaraServiceSpec extends FlatSpec with Matchers with BeforeAndAfterAll {

  private implicit var database: HakurekisteriDriver.backend.Database = _
  private implicit val system = ActorSystem("test-ovara")

  private val config: MockConfig = new MockConfig

  val client = new MockSiirtotiedostoClient

  var ovaraService: OvaraService = _

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

  val haku = Haku(
    Kieliversiot(Some("haku"), None, None),
    "1.1",
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
  class MockHakuActor extends Actor {
    override def receive: Receive = {
      case HakuRequest => sender ! AllHaut(Seq(haku))
      case msg: Int    => sender ! msg.toString
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
      null,
      null
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
}
