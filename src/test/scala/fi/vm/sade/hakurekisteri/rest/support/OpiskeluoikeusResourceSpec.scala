package fi.vm.sade.hakurekisteri.rest.support

import java.util.UUID

import akka.actor.{ActorSystem, Props}
import fi.vm.sade.hakurekisteri.MockConfig
import fi.vm.sade.hakurekisteri.acceptance.tools.FakeAuthorizer
import fi.vm.sade.hakurekisteri.opiskeluoikeus.{
  Opiskeluoikeus,
  OpiskeluoikeusJDBCActor,
  OpiskeluoikeusTable
}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import fi.vm.sade.hakurekisteri.tools.ItPostgres
import fi.vm.sade.hakurekisteri.web.opiskeluoikeus.OpiskeluoikeusResource
import fi.vm.sade.hakurekisteri.web.rest.support.{HakurekisteriSwagger, TestSecurity}
import org.joda.time.{DateTime, LocalDate}
import org.json4s.jackson.Serialization._
import org.scalatest.BeforeAndAfterEach
import org.scalatra.test.scalatest.ScalatraFunSuite
import slick.lifted.TableQuery

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.implicitConversions

class OpiskeluoikeusResourceSpec extends ScalatraFunSuite with BeforeAndAfterEach {
  val now = new DateTime()
  val opOik = Opiskeluoikeus(
    new LocalDate(System.currentTimeMillis()),
    None,
    "1.2.3.987654",
    "4.55.1.90",
    "testmyontaja",
    "testsource"
  )

  implicit val system = ActorSystem()
  implicit var database: Database = _
  implicit val security = new TestSecurity
  implicit val swagger = new HakurekisteriSwagger
  private val mockConfig: MockConfig = new MockConfig

  override def beforeAll(): Unit = {
    database = ItPostgres.getDatabase

    val opiskeluoikeusJournal = new JDBCJournal[Opiskeluoikeus, UUID, OpiskeluoikeusTable](
      TableQuery[OpiskeluoikeusTable],
      config = mockConfig
    )
    val guardedOpiskeluoikeusRekisteri = system.actorOf(
      Props(
        new FakeAuthorizer(
          system.actorOf(Props(new OpiskeluoikeusJDBCActor(opiskeluoikeusJournal, 1, mockConfig)))
        )
      )
    )
    addServlet(new OpiskeluoikeusResource(guardedOpiskeluoikeusRekisteri), "/*")

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
  }

  test("send reasonable Opiskeluoikeus should return 201") {
    implicit val formats = HakurekisteriJsonSupport.format
    post("/", write(opOik), Map("Content-Type" -> "application/json; charset=utf-8")) {
      status should be(201)
    }
  }

  test("send Opiskeluoikeus without komo should return 400") {
    val json =
      "{\"myontaja\":\"1.10.1.10.1\",\"henkiloOid\":\"1.24.12345\",\"aika\":{\"alku\":\"2015-01-01T00:00:00.000Z\",\"loppu\":\"2014-12-31T00:00:00.000Z\"}}"
    post("/", json, Map("Content-Type" -> "application/json; charset=utf-8")) {
      status should be(400)
      body should include("Pakollista kenttää komo ei löydy")
    }
  }

  test("get Opiskeluoikeudet without params should return 400") {
    get("/") {
      status should be(400)
      body should include("illegal query params")
    }
  }
}
