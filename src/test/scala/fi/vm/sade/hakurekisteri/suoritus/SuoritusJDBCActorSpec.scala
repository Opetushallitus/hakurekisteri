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
import org.scalatest.BeforeAndAfterEach
import org.scalatra.test.scalatest.ScalatraFunSuite
import org.slf4j.LoggerFactory

import scala.concurrent.duration.{Duration, _}
import scala.concurrent.{Await, ExecutionContext, Future}


class SuoritusJDBCActorSpec extends ScalatraFunSuite with BeforeAndAfterEach {
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

  override protected def afterEach(): Unit = {
    Await.result(system.terminate(), 15.seconds)
    database.close()
  }


  test("x") {
    run(suoritusrekisteri ? originalSuoritus.copy(henkilo = linkedOid2, tila = "VALMIS"))
    val henkilo1Tilat = run(database.run(sql"select tila from suoritus where henkilo_oid = $linkedOid1".as[String]))
    val henkilo2Tilat = run(database.run(sql"select tila from suoritus where henkilo_oid = $linkedOid2".as[String]))
    henkilo1Tilat should have length 1
    henkilo2Tilat should have length 0
  }

  private def run[T](f: Future[T]): T = Await.result(f, atMost = timeout.duration)

}
