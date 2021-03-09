package fi.vm.sade.hakurekisteri.rest

import java.util.UUID

import akka.actor.{Actor, ActorSystem, Props}
import akka.testkit.TestActorRef
import fi.vm.sade.hakurekisteri.MockConfig
import fi.vm.sade.hakurekisteri.acceptance.tools.FakeAuthorizer
import fi.vm.sade.hakurekisteri.integration.henkilo.MockPersonAliasesProvider
import fi.vm.sade.hakurekisteri.integration.koodisto.{
  GetKoodistoKoodiArvot,
  KoodistoActorRef,
  KoodistoKoodiArvot
}
import fi.vm.sade.hakurekisteri.integration.parametrit.{IsRestrictionActive, ParametritActorRef}
import fi.vm.sade.hakurekisteri.koodisto.MockedKoodistoActor
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

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.implicitConversions

class SuoritusServletSpec extends ScalatraFunSuite with BeforeAndAfterEach {
  val suoritus = Peruskoulu("1.2.3", "KESKEN", LocalDate.now, "1.2.4")

  implicit var system: ActorSystem = _
  implicit var database: Database = _
  implicit val swagger: Swagger = new HakurekisteriSwagger
  implicit val security = new TestSecurity
  private val mockConfig: MockConfig = new MockConfig

  var suoritusJournal: JDBCJournal[Suoritus, UUID, SuoritusTable] = _

  override def beforeAll(): Unit = {
    system = ActorSystem("test-tuo-suoritus")
    database = Database.forURL(ItPostgres.getEndpointURL)
    suoritusJournal =
      new JDBCJournal[Suoritus, UUID, SuoritusTable](TableQuery[SuoritusTable], config = mockConfig)
    val mockParameterActor = new ParametritActorRef(system.actorOf(Props(new Actor {
      override def receive: Actor.Receive = { case IsRestrictionActive(_) =>
        sender ! true
      }
    })))
    val mockKoodistoActor = new KoodistoActorRef(system.actorOf(Props(new Actor {
      override def receive: Actor.Receive = { case q: GetKoodistoKoodiArvot =>
        q.koodistoUri match {
          case "oppiaineetyleissivistava" =>
            sender ! KoodistoKoodiArvot(
              koodistoUri = "oppiaineetyleissivistava",
              arvot = Seq(
                "AI",
                "A1",
                "A12",
                "A2",
                "A22",
                "B1",
                "B2",
                "B22",
                "B23",
                "B3",
                "B32",
                "B33",
                "BI",
                "FI",
                "FY",
                "GE",
                "HI",
                "KE",
                "KO",
                "KS",
                "KT",
                "KU",
                "LI",
                "MA",
                "MU",
                "PS",
                "TE",
                "YH"
              ),
              Map.empty
            )
          case "kieli" =>
            sender ! KoodistoKoodiArvot(
              koodistoUri = "kieli",
              arvot = Seq("FI", "SV", "EN"),
              Map.empty
            )
        }
      }
    })))
    val guardedSuoritusRekisteri = system.actorOf(
      Props(
        new FakeAuthorizer(
          system.actorOf(
            Props(new SuoritusJDBCActor(suoritusJournal, 1, MockPersonAliasesProvider, mockConfig))
          )
        )
      )
    )
    addServlet(
      new SuoritusResource(guardedSuoritusRekisteri, mockParameterActor, mockKoodistoActor),
      "/*"
    )
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
    post(
      "/",
      write(
        VirallinenSuoritus(
          "1.2.246.562.5.00000000001",
          "1.2.246.562.10.00000000001",
          "KESKEN",
          new LocalDate(),
          "1.2.246.562.24.00000000001",
          yksilollistaminen.Ei,
          "FI",
          None,
          false,
          "1.2.246.562.24.00000000001"
        )
      ),
      Map("Content-Type" -> "application/json; charset=utf-8")
    ) {
      body should include("\"vahvistettu\":false")
    }
  }

  test("save vahvistettu suoritus should return vahvistettu:true") {
    post(
      "/",
      write(
        VirallinenSuoritus(
          "1.2.246.562.5.00000000001",
          "1.2.246.562.10.00000000001",
          "KESKEN",
          new LocalDate(),
          "1.2.246.562.24.00000000001",
          yksilollistaminen.Ei,
          "FI",
          None,
          true,
          "1.2.246.562.24.00000000001"
        )
      ),
      Map("Content-Type" -> "application/json; charset=utf-8")
    ) {
      body should include("\"vahvistettu\":true")
    }
  }
}
