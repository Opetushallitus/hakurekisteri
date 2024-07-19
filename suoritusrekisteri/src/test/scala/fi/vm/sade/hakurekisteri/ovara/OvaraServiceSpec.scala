package fi.vm.sade.hakurekisteri.ovara

import akka.actor.ActorSystem
import fi.vm.sade.hakurekisteri.MockConfig
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver
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

  override def beforeAll(): Unit = {
    val journals: DbJournals = new DbJournals(config)
    database = journals.database
    ovaraService = new OvaraService(
      new OvaraDbRepositoryImpl(database),
      client,
      null, //Todo, no test for ensikertalaisuus right now
      null, //Only needed for ensikertalaiset
      1000
    )
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    try super.afterAll()
    finally {
      Await.result(system.terminate(), 15.seconds)
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
