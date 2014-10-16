package fi.vm.sade.hakurekisteri

import fi.vm.sade.hakurekisteri.arvosana.{ArvosanaActor, Arvio410, Arvosana}
import fi.vm.sade.hakurekisteri.integration.hakemus.{HakemusQuery, FullHakemus}
import fi.vm.sade.hakurekisteri.opiskeluoikeus.{Opiskeluoikeus, OpiskeluoikeusActor}
import org.scalatra.test.scalatest.ScalatraFunSuite
import fi.vm.sade.hakurekisteri.suoritus._
import org.joda.time.{LocalDate, DateTime}
import akka.actor.{Actor, Props, ActorSystem}

import fi.vm.sade.hakurekisteri.acceptance.tools.{Peruskoulu, FakeAuthorizer}
import fi.vm.sade.hakurekisteri.opiskelija.{OpiskelijaActor, Opiskelija}
import fi.vm.sade.hakurekisteri.healthcheck.{HealthcheckActor, HealthcheckResource}
import fi.vm.sade.hakurekisteri.storage.repository.{Updated, InMemJournal}
import java.util.UUID
import fi.vm.sade.hakurekisteri.integration.ytl.{Batch, Report, YtlReport}
import org.json4s.JsonAST.JInt
import fi.vm.sade.hakurekisteri.ensikertalainen.{QueriesRunning, QueryCount}
import fi.vm.sade.hakurekisteri.storage.Identified

class HealthcheckResourceSpec extends ScalatraFunSuite {
  val arvosana = Arvosana(UUID.randomUUID(), Arvio410("10"), "AI", None, false, source = "Test")
  val opiskelija = Opiskelija("1.2.3", "9", "9A", "1.2.4", DateTime.now, None, source = "Test")
  val opiskeluoikeus = Opiskeluoikeus(LocalDate.now(), None, "1.2.4", "1.2.5", "1.2.3", source = "Test")
  val suoritus = Peruskoulu("1.2.3", "KESKEN", LocalDate.now,"1.2.4")
  val hakemus = FullHakemus("1.2.5", Some("1.2.4"), ("1.2.5"), None, state =  Some("ACTIVE"), Seq())

  implicit val system = ActorSystem()

  val arvosanaRekisteri = system.actorOf(Props(new ArvosanaActor(seq2journal(Seq(arvosana)))))
  val guardedArvosanaRekisteri = system.actorOf(Props(new FakeAuthorizer(arvosanaRekisteri)))

  val opiskelijaRekisteri = system.actorOf(Props(new OpiskelijaActor(seq2journal(Seq(opiskelija)))))
  val guardedOpiskelijaRekisteri = system.actorOf(Props(new FakeAuthorizer(opiskelijaRekisteri)))

  val opiskeluoikeusRekisteri = system.actorOf(Props(new OpiskeluoikeusActor(seq2journal(Seq(opiskeluoikeus)))))
  val guardedOpiskeluoikeusRekisteri = system.actorOf(Props(new FakeAuthorizer(opiskeluoikeusRekisteri)))

  val suoritusRekisteri = system.actorOf(Props(new SuoritusActor(seq2journal(Seq(suoritus)))))
  val guardedSuoritusRekisteri = system.actorOf(Props(new FakeAuthorizer(suoritusRekisteri)))

  val hakemukset = system.actorOf(Props(new Actor {
    override def receive: Actor.Receive = {
      case h: HakemusQuery => val identify: FullHakemus with Identified[String] = hakemus.identify
        sender ! Seq(identify)
    }
  }))

  val ensikertalainen = system.actorOf(Props(new Actor {
    override def receive: Actor.Receive = {
      case QueryCount => sender ! QueriesRunning(Map("status" -> 1))
    }

  }))


  val ytl = system.actorOf(Props(new Actor {

    override def receive: Actor.Receive = {
      case Report => sender ! YtlReport(Seq(), None)


    }
  }))

  val healthcheck = system.actorOf(Props(new HealthcheckActor(guardedArvosanaRekisteri, guardedOpiskelijaRekisteri, guardedOpiskeluoikeusRekisteri, guardedSuoritusRekisteri, ytl,  hakemukset, ensikertalainen)))

  addServlet(new HealthcheckResource(healthcheck), "/*")

  import org.json4s.jackson.JsonMethods._

  test("healthcheck should return OK and correct resource counts") {
    get("/") {
      println (pretty(parse(body)))
      status should equal (200)
      body should include ("\"status\":\"OK\"")
      parse(body) \\ "arvosanat" \ "count"   should equal(JInt(1))
      parse(body) \\ "opiskelijat" \ "count" should equal(JInt(1))
      parse(body) \\ "opiskeluoikeudet" \ "count" should equal(JInt(1))
      parse(body) \\ "suoritukset" \ "count" should equal(JInt(1))
      parse(body) \\ "hakemukset" \ "count" should equal(JInt(1))
      //body should include ("\"foundHakemukset\":1")
      response.getHeader("Expires") should not be null
    }
  }

  def seq2journal[R <: fi.vm.sade.hakurekisteri.rest.support.Resource[UUID, R]](s:Seq[R]) = {
    val journal = new InMemJournal[R, UUID]
    s.foreach((resource:R) => journal.addModification(Updated(resource.identify(UUID.randomUUID()))))
    journal
  }

}
