package fi.vm.sade.hakurekisteri.rest

import java.util.UUID

import akka.actor.{ActorSystem, Props}
import fi.vm.sade.hakurekisteri.MockConfig
import fi.vm.sade.hakurekisteri.acceptance.tools.FakeAuthorizer
import fi.vm.sade.hakurekisteri.opiskelija.{Opiskelija, OpiskelijaJDBCActor, OpiskelijaTable}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriJsonSupport, JDBCJournal}
import fi.vm.sade.hakurekisteri.tools.ItPostgres
import fi.vm.sade.hakurekisteri.web.opiskelija.OpiskelijaResource
import fi.vm.sade.hakurekisteri.web.rest.support.{HakurekisteriSwagger, TestSecurity}
import org.joda.time.DateTime
import org.json4s.jackson.Serialization._
import org.scalatest.BeforeAndAfterEach
import org.scalatra.test.scalatest.ScalatraFunSuite
import slick.lifted.TableQuery

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.implicitConversions

class OpiskelijaSpec extends ScalatraFunSuite with BeforeAndAfterEach {
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
    val guardedOpiskelijaRekisteri = system.actorOf(
      Props(
        new FakeAuthorizer(
          system.actorOf(Props(new OpiskelijaJDBCActor(opiskelijaJournal, 1, mockConfig)))
        )
      )
    )
    //addServlet(new HakurekisteriResource[Opiskelija](guardedOpiskelijaRekisteri, OpiskelijaQuery(_)) with OpiskelijaSwaggerApi with HakurekisteriCrudCommands[Opiskelija], "/*") //fixme
    addServlet(new OpiskelijaResource(guardedOpiskelijaRekisteri), "/*")

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

  test("send opiskelija should return 201") {
    implicit val formats = HakurekisteriJsonSupport.format
    post("/", write(opiskelija), Map("Content-Type" -> "application/json; charset=utf-8")) {
      status should be(201)
    }
  }

  test("send opiskelija with invalid loppuPaiva should return 400") {
    val json =
      "{\"oppilaitosOid\":\"1.10.1\",\"luokkataso\":\"9\",\"luokka\":\"9A\",\"henkiloOid\":\"1.24.1\",\"alkuPaiva\":\"2015-01-01T00:00:00.000Z\",\"loppuPaiva\":\"2014-12-31T00:00:00.000Z\"}"
    post("/", json, Map("Content-Type" -> "application/json; charset=utf-8")) {
      status should be(400)
      body should include("loppuPaiva must be after alkuPaiva")
    }
  }
}
