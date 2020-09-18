package fi.vm.sade.hakurekisteri.rest

import java.util.UUID

import akka.actor.{ActorSystem, Props}
import fi.vm.sade.hakurekisteri.MockConfig
import fi.vm.sade.hakurekisteri.acceptance.tools.FakeAuthorizer
import fi.vm.sade.hakurekisteri.arvosana._
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriJsonSupport, JDBCJournal}
import fi.vm.sade.hakurekisteri.storage.repository.Updated
import fi.vm.sade.hakurekisteri.tools.ItPostgres
import fi.vm.sade.hakurekisteri.web.arvosana.EmptyLisatiedotResource
import fi.vm.sade.hakurekisteri.web.rest.support._
import org.scalatra.test.scalatest.ScalatraFunSuite

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.implicitConversions

class ArvosanaEmptyLisatietoSpec extends ScalatraFunSuite {
  test("query should return 200") {
    implicit val system = ActorSystem()
    implicit val database = Database.forURL(ItPostgres.getEndpointURL)
    implicit val security = new TestSecurity
    val mockConfig: MockConfig = new MockConfig

    val arvosanaJournal =
      new JDBCJournal[Arvosana, UUID, ArvosanaTable](TableQuery[ArvosanaTable], config = mockConfig)
    (0 until 10).foreach((i) => {
      arvosanaJournal.addModification(
        Updated(
          Arvosana(
            UUID.randomUUID(),
            Arvio410("10"),
            "AI",
            if (i % 2 == 0) None else Some(""),
            valinnainen = false,
            None,
            "Test",
            Map()
          ).identify(UUID.randomUUID())
        )
      )
    })
    val arvosanaRekisteri =
      system.actorOf(Props(new ArvosanaJDBCActor(arvosanaJournal, 1, mockConfig)))
    val guardedArvosanaRekisteri = system.actorOf(Props(new FakeAuthorizer(arvosanaRekisteri)))
    implicit val swagger = new HakurekisteriSwagger

    addServlet(new EmptyLisatiedotResource(guardedArvosanaRekisteri), "/*")

    get("/") {
      status should equal(200)
      implicit val formats = HakurekisteriJsonSupport.format
      import org.json4s.jackson.Serialization.read
      val arvosanat = read[Seq[Arvosana]](body)
      arvosanat.length should equal(5)
    }
    Await.result(system.terminate(), 15.seconds)
    database.close()
  }
}
