package fi.vm.sade.hakurekisteri.rest

import java.util.UUID

import akka.actor.{Actor, ActorSystem, Props}
import fi.vm.sade.hakurekisteri.acceptance.tools.FakeAuthorizer
import fi.vm.sade.hakurekisteri.integration.parametrit.IsRestrictionActive
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriJsonSupport, JDBCJournal}
import fi.vm.sade.hakurekisteri.storage.repository.Updated
import fi.vm.sade.hakurekisteri.suoritus._
import fi.vm.sade.hakurekisteri.tools.{ItPostgres, Peruskoulu}
import fi.vm.sade.hakurekisteri.web.rest.support._
import fi.vm.sade.hakurekisteri.web.suoritus.SuoritusResource
import fi.vm.sade.utils.tcp.ChooseFreePort
import org.joda.time.LocalDate
import org.json4s.jackson.Serialization._
import org.scalatra.swagger.Swagger
import org.scalatra.test.scalatest.ScalatraFunSuite

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.language.implicitConversions

class SuoritusServletSpec extends ScalatraFunSuite {
  implicit val swagger: Swagger = new HakurekisteriSwagger
  val suoritus = Peruskoulu("1.2.3", "KESKEN", LocalDate.now,"1.2.4")
  implicit val system = ActorSystem("test-tuo-suoritus")
  implicit val ec: ExecutionContext = system.dispatcher
  val portChooser = new ChooseFreePort()
  val itDb = new ItPostgres(portChooser)
  itDb.start()
  implicit val database = Database.forURL(s"jdbc:postgresql://localhost:${portChooser.chosenPort}/suoritusrekisteri")
  implicit val security = new TestSecurity

  val suoritusJournal = new JDBCJournal[Suoritus, UUID, SuoritusTable](TableQuery[SuoritusTable])
  suoritusJournal.addModification(Updated(suoritus.identify))
  val suoritusRekisteri = system.actorOf(Props(new SuoritusJDBCActor(suoritusJournal, 1)))
  val guardedSuoritusRekisteri = system.actorOf(Props(new FakeAuthorizer(suoritusRekisteri)))
  val mockParameterActor = system.actorOf(Props(new Actor {
    override def receive: Actor.Receive = {
      case IsRestrictionActive(_) => sender ! true
    }
  }))
  addServlet(new SuoritusResource(guardedSuoritusRekisteri, mockParameterActor, SuoritusQuery(_)), "/*")

  test("get root should return 200") {
    get("/") {
      status should equal (200)
    }
  }

  implicit val formats = HakurekisteriJsonSupport.format

  test("save vahvistamaton suoritus should return vahvistettu:false") {
    post("/", write(VirallinenSuoritus("1.2.246.562.5.00000000001", "1.2.246.562.10.00000000001", "KESKEN", new LocalDate(), "1.2.246.562.24.00000000001", yksilollistaminen.Ei, "FI", None, false, "1.2.246.562.24.00000000001")), Map("Content-Type" -> "application/json; charset=utf-8")) {
      body should include ("\"vahvistettu\":false")
    }
  }

  test("save vahvistettu suoritus should return vahvistettu:true") {
    post("/", write(VirallinenSuoritus("1.2.246.562.5.00000000001", "1.2.246.562.10.00000000001", "KESKEN", new LocalDate(), "1.2.246.562.24.00000000001", yksilollistaminen.Ei, "FI", None, true, "1.2.246.562.24.00000000001")), Map("Content-Type" -> "application/json; charset=utf-8")) {
      body should include ("\"vahvistettu\":true")
    }
  }

  override def stop(): Unit = {
    Await.result(system.terminate(), 15.seconds)
    database.close()
    itDb.stop()
    super.stop()
  }
}
