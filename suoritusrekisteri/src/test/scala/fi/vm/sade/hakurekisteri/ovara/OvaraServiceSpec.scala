package fi.vm.sade.hakurekisteri.ovara

import akka.actor.ActorSystem
import fi.vm.sade.hakurekisteri.MockConfig
import fi.vm.sade.hakurekisteri.tools.ItPostgres
import org.scalatest.{FlatSpec, Matchers}
import support.{DbJournals}

class OvaraServiceSpec extends FlatSpec with Matchers {

  private implicit val database = ItPostgres.getDatabase
  private implicit val system = ActorSystem("test-ovara")

  private val config: MockConfig = new MockConfig
  private val journals: DbJournals = new DbJournals(config)

  val ovaraRepository = new OvaraDbRepositoryImpl(journals.database)
  val client = new MockSiirtotiedostoClient

  private val ovaraService = new OvaraService(
    ovaraRepository,
    client,
    null, //Todo, no test for ensikertalaisuus right now
    null, //Only needed for ensikertalaiset
    1000
  )

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
