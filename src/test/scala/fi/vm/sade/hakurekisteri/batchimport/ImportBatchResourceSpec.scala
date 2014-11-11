package fi.vm.sade.hakurekisteri.batchimport

import java.util.UUID

import akka.actor.{ActorSystem, Props}
import fi.vm.sade.hakurekisteri.acceptance.tools.{FakeAuthorizer, TestSecurity}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.simple._
import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriSwagger, JDBCJournal}
import org.scalatra.swagger.Swagger
import org.scalatra.test.scalatest.ScalatraFunSuite


class ImportBatchResourceSpec extends ScalatraFunSuite {
  implicit val swagger: Swagger = new HakurekisteriSwagger
  implicit val system = ActorSystem("test-import-batch")

  implicit val database = Database.forURL("jdbc:h2:mem:importbatchtest;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
  val eraJournal = new JDBCJournal[ImportBatch, UUID, ImportBatchTable](TableQuery[ImportBatchTable])
  val eraRekisteri = system.actorOf(Props(new ImportBatchActor(eraJournal, 5)))
  val authorized = system.actorOf(Props(new FakeAuthorizer(eraRekisteri)))

  addServlet(new ImportBatchResource(authorized, (foo) => ImportBatchQuery(None))("identifier", "test", "data") with TestSecurity, "/")

  test("post should return 201 created") {
    post("/", "<batch><data>foo</data></batch>") {
      response.status should be(201)
    }
  }

}
