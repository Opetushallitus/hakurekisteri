package fi.vm.sade.hakurekisteri.rest

import java.util.UUID

import akka.actor.{ActorSystem, Props}
import fi.vm.sade.hakurekisteri.acceptance.tools.FakeAuthorizer
import fi.vm.sade.hakurekisteri.arvosana._
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.hakurekisteri.storage.repository.{InMemJournal, Updated}
import fi.vm.sade.hakurekisteri.web.arvosana.EmptyLisatiedotResource
import fi.vm.sade.hakurekisteri.web.rest.support._
import org.scalatra.test.scalatest.ScalatraFunSuite

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.implicitConversions

class ArvosanaEmptyLisatietoSpec extends ScalatraFunSuite {
  implicit val system = ActorSystem()
  implicit val security = new TestSecurity
  def arvosanajournal[R <: fi.vm.sade.hakurekisteri.rest.support.Resource[UUID, R]]: InMemJournal[Arvosana, UUID] = {
    val journal = new InMemJournal[Arvosana, UUID]
    (0 until 10).foreach( (i) => {
      journal.addModification(Updated(Arvosana(UUID.randomUUID(), Arvio410("10"), "AI", if(i%2==0) None else Some(""), valinnainen = false, None, "Test", Map()).identify(UUID.randomUUID())))
    })
    journal
  }

  val arvosanaRekisteri = system.actorOf(Props(new ArvosanaActor(arvosanajournal)))
  val guardedArvosanaRekisteri = system.actorOf(Props(new FakeAuthorizer(arvosanaRekisteri)))
  implicit val swagger = new HakurekisteriSwagger

  addServlet(new EmptyLisatiedotResource(guardedArvosanaRekisteri), "/*")
  test("query should return 200") {
    get("/") {
      status should equal (200)
      implicit val formats = HakurekisteriJsonSupport.format
      import org.json4s.jackson.Serialization.read
      val arvosanat = read[Seq[Arvosana]](body)
      arvosanat.length should equal (5)
    }
  }

  override def stop(): Unit = {
    Await.result(system.terminate(), 15.seconds)
  }
}
