package fi.vm.sade.hakurekisteri.suoritus

import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor.{ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.integration.henkilo.{MockOppijaNumeroRekisteri, MockPersonAliasesProvider}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import fi.vm.sade.hakurekisteri.rest.support.JDBCJournal
import fi.vm.sade.hakurekisteri.tools.ItPostgres
import org.joda.time.LocalDate
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FlatSpec, Matchers}
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}


class SuoritusJDBCActorSpec extends FlatSpec with BeforeAndAfterEach with  BeforeAndAfterAll with Matchers {
  val logger = LoggerFactory.getLogger(getClass)

  val linkedOid1: String = MockOppijaNumeroRekisteri.linkedTestPersonOids.head
  val linkedOid2: String = MockOppijaNumeroRekisteri.linkedTestPersonOids(1)
  val originalSuoritus: VirallinenSuoritus = VirallinenSuoritus(komo = "komo", myontaja = "myontaja", tila = "KESKEN", valmistuminen = new LocalDate(),
    henkilo = linkedOid1, yksilollistaminen = yksilollistaminen.Ei, suoritusKieli = "FI", lahde = "1.2.246.562.10.1234", vahv = false)

  implicit val database = Database.forURL(ItPostgres.getEndpointURL)
  implicit val system = ActorSystem("test-jdbc")
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val timeout: Timeout = Timeout(30, TimeUnit.SECONDS)
  val suoritusJournal = new JDBCJournal[Suoritus, UUID, SuoritusTable](TableQuery[SuoritusTable])
  val suoritusrekisteri = system.actorOf(Props(new SuoritusJDBCActor(suoritusJournal, 1, MockPersonAliasesProvider)))

  override protected def beforeEach(): Unit = {
    ItPostgres.reset()
    run(suoritusrekisteri ? originalSuoritus)
  }

  override protected def afterAll(): Unit = {
    Await.result(system.terminate(), 15.seconds)
    database.close()
  }

  behavior of "SuoritusJDBCActor"

  it should "update suoritus with linked person oid retains original person oid in database" in {
    val original = run((suoritusrekisteri ? SuoritusQuery(henkilo = Some(linkedOid2))).mapTo[Seq[VirallinenSuoritus]]).head
    val newCopy = originalSuoritus.copy(henkilo = linkedOid2, tila = "VALMIS")
    println("original: " + original)
    println("Kopio: " + newCopy)
    run(suoritusrekisteri ? newCopy)
    val henkilo1Tilat = run(database.run(sql"select tila from suoritus where henkilo_oid = $linkedOid1 and current".as[String]))
    val henkilo2Tilat = run(database.run(sql"select tila from suoritus where henkilo_oid = $linkedOid2 and current".as[String]))
    henkilo1Tilat should have length 1
    henkilo2Tilat should have length 0
  }

  it should "update suoritus with original person oid updates suoritus" in {
    run(suoritusrekisteri ? originalSuoritus.copy(tila = "VALMIS"))
    val henkilo1Tilat = run(database.run(sql"select tila from suoritus where henkilo_oid = $linkedOid1 and current".as[String]))
    henkilo1Tilat should have length 1
    henkilo1Tilat.head should equal("VALMIS")
  }

  it should "create new suoritus when updating field included in deduplication" in {
    val s: VirallinenSuoritus = run((suoritusrekisteri ? SuoritusQuery(henkilo = Some(linkedOid2))).mapTo[Seq[VirallinenSuoritus]]).head
    run(suoritusrekisteri ? s.copy(komo = "uusikomo"))
    val henkilo1Komot = run(database.run(sql"select komo from suoritus where henkilo_oid = $linkedOid1 and current".as[String]))
    val henkilo2Komot = run(database.run(sql"select komo from suoritus where henkilo_oid = $linkedOid2 and current".as[String]))
    henkilo1Komot should have length 1
    henkilo2Komot should have length 1
    henkilo1Komot.head should equal("komo")
    henkilo2Komot.head should equal("uusikomo")
  }

  private def run[T](f: Future[T]): T = Await.result(f, atMost = timeout.duration)

}
