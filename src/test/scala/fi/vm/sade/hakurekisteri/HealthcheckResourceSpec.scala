package fi.vm.sade.hakurekisteri

import fi.vm.sade.hakurekisteri.integration.hakemus.{HakemusQuery, FullHakemus}
import org.scalatra.test.scalatest.ScalatraFunSuite
import fi.vm.sade.hakurekisteri.suoritus._
import org.joda.time.{LocalDate, DateTime}
import akka.actor.{Actor, Props, ActorSystem}

import fi.vm.sade.hakurekisteri.acceptance.tools.{FakeAuthorizer}
import fi.vm.sade.hakurekisteri.opiskelija.{OpiskelijaActor, Opiskelija}
import fi.vm.sade.hakurekisteri.healthcheck.{HealthcheckActor, HealthcheckResource}
import fi.vm.sade.hakurekisteri.storage.repository.{Updated, InMemJournal}
import java.util.UUID

class HealthcheckResourceSpec extends ScalatraFunSuite {
  val suoritus = Peruskoulu("1.2.3", "KESKEN", LocalDate.now,"1.2.4")
  val opiskelija = Opiskelija("1.2.3", "9", "9A", "1.2.4", DateTime.now, None)

  val hakemus = FullHakemus("1.2.5", Some("1.2.4"), ("1.2.5"), None, state =  Some("ACTIVE"))
  implicit val system = ActorSystem()

  val suoritusRekisteri = system.actorOf(Props(new SuoritusActor(seq2journal(Seq(suoritus)))))
  val guardedSuoritusRekisteri = system.actorOf(Props(new FakeAuthorizer(suoritusRekisteri)))

  val opiskelijaRekisteri = system.actorOf(Props(new OpiskelijaActor(seq2journal(Seq(opiskelija)))))
  val guardedOpiskelijaRekisteri = system.actorOf(Props(new FakeAuthorizer(opiskelijaRekisteri)))

  val hakemukset = system.actorOf(Props(new Actor {
    override def receive: Actor.Receive = {
      case h: HakemusQuery => sender ! Seq(FullHakemus.identify(hakemus))
    }
  }))

  val healthcheck = system.actorOf(Props(new HealthcheckActor(guardedSuoritusRekisteri, guardedOpiskelijaRekisteri, hakemukset)))

  addServlet(new HealthcheckResource(healthcheck), "/*")

  test("healthcheck should return OK and correct resource counts") {
    get("/") {
      status should equal (200)
      body should include ("OK")
      body should include ("\"suoritukset\":1")
      body should include ("\"opiskelijat\":1")
      body should include ("\"hakemukset\":1")
      response.getHeader("Expires") should not be null
    }
  }

  def seq2journal[R <: fi.vm.sade.hakurekisteri.rest.support.Resource[UUID]](s:Seq[R]) = {
    val journal = new InMemJournal[R, UUID]
    s.foreach((resource:R) => journal.addModification(Updated(resource.identify(UUID.randomUUID()))))
    journal
  }

}
