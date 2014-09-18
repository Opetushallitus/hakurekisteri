package fi.vm.sade.hakurekisteri

import org.scalatra.test.scalatest.{ScalatraFunSuite, ScalatraFlatSpec}
import akka.actor.{Props, ActorSystem}
import java.util.{UUID, Date}
import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriCrudCommands, HakurekisteriResource, HakurekisteriSwagger}
import fi.vm.sade.hakurekisteri.suoritus._
import org.joda.time.{LocalDate, DateTime}
import fi.vm.sade.hakurekisteri.storage.repository.{Updated, InMemJournal}
import fi.vm.sade.hakurekisteri.opiskelija.{CreateOpiskelijaCommand, Opiskelija}
import fi.vm.sade.hakurekisteri.acceptance.tools.{Peruskoulu, TestSecurity, FakeAuthorizer}

class SuoritusServletSpec extends ScalatraFunSuite {
  val suoritus = Peruskoulu("1.2.3", "KESKEN", LocalDate.now,"1.2.4")
  implicit val system = ActorSystem()
  implicit def seq2journal[R <: fi.vm.sade.hakurekisteri.rest.support.Resource[UUID]](s:Seq[R]) = {
    val journal = new InMemJournal[R, UUID]
    s.foreach((resource:R) => journal.addModification(Updated(resource.identify(UUID.randomUUID()))))
    journal
  }
  val suoritusRekisteri = system.actorOf(Props(new SuoritusActor(Seq(suoritus))))
  val guardedSuoritusRekisteri = system.actorOf(Props(new FakeAuthorizer(suoritusRekisteri)))
  implicit val swagger = new HakurekisteriSwagger

  addServlet(new HakurekisteriResource[Suoritus, CreateSuoritusCommand](guardedSuoritusRekisteri, SuoritusQuery(_ )) with SuoritusSwaggerApi with HakurekisteriCrudCommands[Suoritus, CreateSuoritusCommand] with TestSecurity, "/*")

  test("get root should return 200") {
    get("/") {
      status should equal (200)
    }
  }
}
