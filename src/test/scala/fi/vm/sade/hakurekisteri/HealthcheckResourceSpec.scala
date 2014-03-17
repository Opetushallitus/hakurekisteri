package fi.vm.sade.hakurekisteri

import org.scalatra.test.scalatest.ScalatraFunSuite
import fi.vm.sade.hakurekisteri.suoritus._
import org.joda.time.DateTime
import akka.actor.{Props, ActorSystem}

import fi.vm.sade.hakurekisteri.acceptance.tools.{FakeAuthorizer}
import fi.vm.sade.hakurekisteri.opiskelija.{OpiskelijaActor, Opiskelija}
import fi.vm.sade.hakurekisteri.healthcheck.{HealthcheckActor, HealthcheckResource}
import fi.vm.sade.hakurekisteri.storage.repository.InMemJournal
import java.util.UUID

class HealthcheckResourceSpec extends ScalatraFunSuite {
  val suoritus = Peruskoulu("1.2.3", "KESKEN",  DateTime.now,"1.2.4")
  val opiskelija = Opiskelija("1.2.3", "9", "9A", "1.2.4", DateTime.now, None)
  implicit val system = ActorSystem()

  val suoritusRekisteri = system.actorOf(Props(new SuoritusActor(seq2journal(Seq(suoritus)))))
  val guardedSuoritusRekisteri = system.actorOf(Props(new FakeAuthorizer(suoritusRekisteri)))

  val opiskelijaRekisteri = system.actorOf(Props(new OpiskelijaActor(seq2journal(Seq(opiskelija)))))
  val guardedOpiskelijaRekisteri = system.actorOf(Props(new FakeAuthorizer(opiskelijaRekisteri)))

  val healthcheck = system.actorOf(Props(new HealthcheckActor(guardedSuoritusRekisteri, guardedOpiskelijaRekisteri)))

  addServlet(new HealthcheckResource(healthcheck), "/*")

  test("healthcheck should return OK and correct resource counts") {
    get("/") {
      status should equal (200)
      body should include ("OK")
      body should include ("\"suoritukset\":1")
      body should include ("\"opiskelijat\":1")
      response.getHeader("Expires") should not be null
    }
  }

  def seq2journal[R <: fi.vm.sade.hakurekisteri.rest.support.Resource](s:Seq[R]) = {
    val journal = new InMemJournal[R]
    s.foreach((resource:R) => journal.addModification(resource.identify(UUID.randomUUID())))
    journal
  }

}
