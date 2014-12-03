package fi.vm.sade.hakurekisteri.db

import java.nio.charset.Charset
import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor.{Props, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.acceptance.tools.FakeAuthorizer
import fi.vm.sade.hakurekisteri.rest.support.JDBCJournal
import fi.vm.sade.hakurekisteri.suoritus._
import org.h2.tools.RunScript
import org.joda.time.LocalDate
import org.scalatra.test.scalatest.ScalatraFunSuite
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver
import HakurekisteriDriver.simple._
import org.slf4j.LoggerFactory

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Await, Future}
import scala.concurrent.duration._


class JDBCJournalReloadSpec extends ScalatraFunSuite {
  val logger = LoggerFactory.getLogger(getClass)
  implicit val database = Database.forURL("jdbc:h2:file:test", driver = "org.h2.Driver")

  override def stop(): Unit = {
    RunScript.execute("jdbc:h2:file:test", "", "", "classpath:clear-h2.sql", Charset.forName("UTF-8"), false)
    super.stop()
  }

  def createSystemAndInsertAndShutdown(henkilot: Stream[UUID]) = {
    implicit val system = ActorSystem("test-jdbc")
    implicit val ec: ExecutionContext = system.dispatcher

    val suoritusJournal = new JDBCJournal[Suoritus, UUID, SuoritusTable](TableQuery[SuoritusTable])
    val suoritusrekisteri = system.actorOf(Props(new SuoritusActor(suoritusJournal)))
    val authorized = system.actorOf(Props(new FakeAuthorizer(suoritusrekisteri)))

    implicit val timeout: Timeout = 30.seconds
    val now = new LocalDate()

    val yoSuoritukset: Stream[Future[VirallinenSuoritus]] = henkilot.map((henkilo: UUID) => {
      (authorized ? VirallinenSuoritus(
        komo = "1.2.246.562.5.2013061010184237348007",
        myontaja = "1.2.246.562.10.43628088406",
        henkilo = henkilo.toString,
        yksilollistaminen = yksilollistaminen.Ei,
        suoritusKieli = "FI",
        lahde = "1.2.246.562.10.43628088406",
        tila = "VALMIS",
        valmistuminen = now
      )).mapTo[VirallinenSuoritus]
    })

    Await.result(Future.sequence(yoSuoritukset), Duration(30, TimeUnit.SECONDS))

    val suoritusFuture = (authorized ? SuoritusQuery()).mapTo[Seq[Suoritus]]

    val suoritukset = Await.result(suoritusFuture, Duration(30, TimeUnit.SECONDS))

    system.shutdown()
    system.awaitTermination()

    suoritukset
  }

  test("suoritukset should be deduplicated after reloading the journal") {
    val amount = 5
    val henkilot = Stream.continually(java.util.UUID.randomUUID).take(amount)

    val suoritukset = createSystemAndInsertAndShutdown(henkilot)

    val suoritukset2 = createSystemAndInsertAndShutdown(henkilot)

    suoritukset2.size should be (henkilot.length)
  }

}
