package fi.vm.sade.hakurekisteri.rest

import java.util.UUID

import akka.actor.{Actor, ActorSystem, Props}
import fi.vm.sade.hakurekisteri.acceptance.tools.FakeAuthorizer
import fi.vm.sade.hakurekisteri.integration.henkilo.MockOppijaNumeroRekisteri
import fi.vm.sade.hakurekisteri.integration.parametrit.IsRestrictionActive
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriJsonSupport, JDBCJournal}
import fi.vm.sade.hakurekisteri.storage.repository.Updated
import fi.vm.sade.hakurekisteri.suoritus._
import fi.vm.sade.hakurekisteri.tools.{ItPostgres, Peruskoulu}
import fi.vm.sade.hakurekisteri.web.rest.support._
import fi.vm.sade.hakurekisteri.web.suoritus.SuoritusResource
import org.joda.time.LocalDate
import org.json4s.jackson.Serialization._
import org.scalatest.BeforeAndAfterEach
import org.scalatra.swagger.Swagger
import org.scalatra.test.scalatest.ScalatraFunSuite

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.language.implicitConversions

class SuoritusServletSpec extends ScalatraFunSuite with BeforeAndAfterEach {
  val suoritus = Peruskoulu("1.2.3", "KESKEN", LocalDate.now, "1.2.4")

  implicit var system: ActorSystem = _
  implicit var database: Database = _
  implicit val swagger: Swagger = new HakurekisteriSwagger
  implicit val security = new TestSecurity

  var suoritusJournal: JDBCJournal[Suoritus, UUID, SuoritusTable] = _

  override def beforeAll(): Unit = {
    system = ActorSystem("test-tuo-suoritus")
    database = Database.forURL(ItPostgres.getEndpointURL)
    suoritusJournal = new JDBCJournal[Suoritus, UUID, SuoritusTable](TableQuery[SuoritusTable])
    val mockParameterActor = system.actorOf(Props(new Actor {
      override def receive: Actor.Receive = {
        case IsRestrictionActive(_) => sender ! true
      }
    }))
    val guardedSuoritusRekisteri = system.actorOf(Props(new FakeAuthorizer(system.actorOf(Props(new SuoritusJDBCActor(suoritusJournal, 1))))))
    addServlet(new SuoritusResource(guardedSuoritusRekisteri, mockParameterActor, MockOppijaNumeroRekisteri), "/*")
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    try super.afterAll()
    finally {
      Await.result(system.terminate(), 15.seconds)
      database.close()
    }
  }

  override def beforeEach(): Unit = {
    ItPostgres.reset()
    suoritusJournal.addModification(Updated(suoritus.identify))
  }

  test("get root should return 200") {
    get("/") {
      status should equal(200)
    }
  }

  implicit val formats = HakurekisteriJsonSupport.format

  test("save vahvistamaton suoritus should return vahvistettu:false") {
    post("/", write(VirallinenSuoritus("1.2.246.562.5.00000000001", "1.2.246.562.10.00000000001", "KESKEN", new LocalDate(), "1.2.246.562.24.00000000001", yksilollistaminen.Ei, "FI", None, false, "1.2.246.562.24.00000000001")), Map("Content-Type" -> "application/json; charset=utf-8")) {
      body should include("\"vahvistettu\":false")
    }
  }

  test("save vahvistettu suoritus should return vahvistettu:true") {
    post("/", write(VirallinenSuoritus("1.2.246.562.5.00000000001", "1.2.246.562.10.00000000001", "KESKEN", new LocalDate(), "1.2.246.562.24.00000000001", yksilollistaminen.Ei, "FI", None, true, "1.2.246.562.24.00000000001")), Map("Content-Type" -> "application/json; charset=utf-8")) {
      body should include("\"vahvistettu\":true")
    }
  }
}
