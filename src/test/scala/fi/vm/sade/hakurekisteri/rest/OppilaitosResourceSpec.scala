package fi.vm.sade.hakurekisteri.rest

import java.util.UUID
import akka.actor.{ActorSystem, Props}
import fi.vm.sade.hakurekisteri.MockConfig
import fi.vm.sade.hakurekisteri.acceptance.tools.FakeAuthorizer
import fi.vm.sade.hakurekisteri.opiskelija.{Opiskelija, OpiskelijaJDBCActor, OpiskelijaTable}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import fi.vm.sade.hakurekisteri.rest.support.JDBCJournal
import fi.vm.sade.hakurekisteri.storage.repository.Updated
import fi.vm.sade.hakurekisteri.tools.ItPostgres
import fi.vm.sade.hakurekisteri.web.oppilaitos.OppilaitosResource
import fi.vm.sade.hakurekisteri.web.rest.support.{HakurekisteriSwagger, TestSecurity}
import org.joda.time.DateTime
import org.scalatest.BeforeAndAfterEach
import org.scalatra.test.scalatest.ScalatraFunSuite
import slick.lifted.TableQuery

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.implicitConversions

class OppilaitosResourceSpec extends ScalatraFunSuite with BeforeAndAfterEach {
  val now = new DateTime()
  val opiskelija = Opiskelija("1.10.1", "9", "9A", "1.24.1", now, Some(now), "test")

  implicit val system = ActorSystem()
  implicit var database: Database = _
  implicit val security = new TestSecurity
  implicit val swagger = new HakurekisteriSwagger
  private val mockConfig: MockConfig = new MockConfig

  override def beforeAll(): Unit = {
    database = ItPostgres.getDatabase
    val opiskelijaJournal = new JDBCJournal[Opiskelija, UUID, OpiskelijaTable](
      TableQuery[OpiskelijaTable],
      config = mockConfig
    )
    opiskelijaJournal.addModification(
      Updated(
        Opiskelija(
          "1.2.246.562.10.00000000001",
          "9",
          "9A",
          "1.2.246.562.24.61781310000",
          DateTime.now.minusYears(2),
          Some(DateTime.now.plusWeeks(1)),
          "source"
        ).identify
      )
    )
    opiskelijaJournal.addModification(
      Updated(
        Opiskelija(
          "1.2.246.562.10.00000000001",
          "10",
          "10A",
          "1.2.246.562.24.61781310001",
          DateTime.now.minusYears(2),
          Some(DateTime.now.plusWeeks(1)),
          "source"
        ).identify
      )
    )
    opiskelijaJournal.addModification(
      Updated(
        Opiskelija(
          "1.2.246.562.10.00000000001",
          "9",
          "9B",
          "1.2.246.562.24.61781310003",
          DateTime.now.minusYears(5),
          Some(DateTime.now.minusYears(3)),
          "source"
        ).identify
      )
    )
    val guardedOpiskelijaRekisteri = system.actorOf(
      Props(
        new FakeAuthorizer(
          system.actorOf(Props(new OpiskelijaJDBCActor(opiskelijaJournal, 1, mockConfig)))
        )
      )
    )
    addServlet(new OppilaitosResource(guardedOpiskelijaRekisteri), "/*")

    super.beforeAll()
  }

  override def afterAll(): Unit = {
    try super.afterAll()
    finally {
      Await.result(system.terminate(), 15.seconds)
      database.close()
    }
  }

  test("returns 404 if no parameters are given") {
    get("/") {
      response.status should be(404)
    }
  }

  test("returns all students for oppilaitos") {
    get("/1.2.246.562.10.00000000001/opiskelijat") {
      response.status should be(200)
      response.body should include("1.2.246.562.24.61781310000")
      response.body should include("9A")
      response.body should include("1.2.246.562.24.61781310001")
      response.body should include("10A")
      response.body should not include ("1.2.246.562.24.61781310003")
      response.body should not include ("9B")
    }
  }

  test("returns only student with level 10 for oppilaitos") {
    get("/1.2.246.562.10.00000000001/opiskelijat?luokkaTasot=10") {
      response.status should be(200)
      response.body should not include ("1.2.246.562.24.61781310000")
      response.body should not include ("9A")
      response.body should include("1.2.246.562.24.61781310001")
      response.body should include("10A")
      response.body should not include ("1.2.246.562.24.61781310003")
      response.body should not include ("9B")
    }
  }

  test("returns both students with level 10 and 9 for oppilaitos") {
    get("/1.2.246.562.10.00000000001/opiskelijat?luokkaTasot=10,9") {
      response.status should be(200)
      response.body should include("1.2.246.562.24.61781310000")
      response.body should include("9A")
      response.body should include("1.2.246.562.24.61781310001")
      response.body should include("10A")
      response.body should not include ("1.2.246.562.24.61781310003")
      response.body should not include ("9B")
    }
  }

  test("returns one student using year for oppilaitos") {
    val year = DateTime.now.minusYears(3).getYear
    get("/1.2.246.562.10.00000000001/opiskelijat?vuosi=" + year) {
      response.status should be(200)
      response.body should not include ("1.2.246.562.24.61781310000")
      response.body should not include ("9A")
      response.body should not include ("1.2.246.562.24.61781310001")
      response.body should not include ("10A")
      response.body should include("1.2.246.562.24.61781310003")
      response.body should include("9B")
    }
  }

  test("returns classes for the school") {
    get("/1.2.246.562.10.00000000001/luokat") {
      response.status should be(200)
      response.body should include("9A")
      response.body should include("10A")
      response.body should not include ("9B") // Student of the class is not from the current year.
    }
  }

  test("returns classes for the school for given year") {
    val year = DateTime.now.minusYears(3).getYear
    get("/1.2.246.562.10.00000000001/luokat?vuosi=" + year) {
      response.status should be(200)
      response.body should not include ("9A")
      response.body should not include ("10A")
      response.body should include("9B")
    }
  }
}
