package fi.vm.sade.hakurekisteri.arvosana

import java.util.UUID

import akka.actor.{Props, ActorSystem}
import fi.vm.sade.hakurekisteri.acceptance.tools.FakeAuthorizer
import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriJsonSupport, TestSecurity}
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.storage.repository.{Updated, InMemJournal}
import fi.vm.sade.hakurekisteri.web.arvosana.{ArvosanaSwaggerApi, CreateArvosanaCommand}
import fi.vm.sade.hakurekisteri.web.rest.support.{HakurekisteriCrudCommands, HakurekisteriResource, HakurekisteriSwagger}
import org.joda.time.LocalDate
import org.json4s.jackson.Serialization._
import org.scalatra.test.scalatest.ScalatraFunSuite

import scala.language.implicitConversions

class ArvosanaSerializeSpec extends ScalatraFunSuite {
  val arvosana1 = Arvosana(UUID.randomUUID(), Arvio410("10"), "AI", Some("FI"), valinnainen = false, None, "Test")
  val arvosana12 = Arvosana(UUID.randomUUID(), Arvio410("10"), "AI", Some("FI"), valinnainen = true, None, "Test", Some(0))
  val arvosana2 = Arvosana(UUID.randomUUID(), ArvioYo("L", Some(100)), "AI", Some("FI"), valinnainen = false, Some(new LocalDate()), "Test")
  val arvosana3 = Arvosana(UUID.randomUUID(), ArvioOsakoe("10"), "AI", Some("FI"), valinnainen = false, Some(new LocalDate()), "Test")

  implicit val system = ActorSystem()
  implicit def seq2journal[R <: fi.vm.sade.hakurekisteri.rest.support.Resource[UUID, R]](s:Seq[R]): InMemJournal[R, UUID] = {
    val journal = new InMemJournal[R, UUID]
    s.foreach((resource:R) => journal.addModification(Updated(resource.identify(UUID.randomUUID()))))
    journal
  }
  val arvosanaRekisteri = system.actorOf(Props(new ArvosanaActor(Seq(arvosana1, arvosana12, arvosana2, arvosana3))))
  val guardedArvosanaRekisteri = system.actorOf(Props(new FakeAuthorizer(arvosanaRekisteri)))
  implicit val swagger = new HakurekisteriSwagger

  addServlet(new HakurekisteriResource[Arvosana, CreateArvosanaCommand](guardedArvosanaRekisteri, ArvosanaQuery(_)) with ArvosanaSwaggerApi with HakurekisteriCrudCommands[Arvosana, CreateArvosanaCommand] with TestSecurity, "/*")

  test("get root should return 200") {
    get("/") {
      status should equal (200)
    }
  }

  test("body should contain a sequence with three arvosanas") {
    get("/") {
      implicit val formats = HakurekisteriJsonSupport.format
      import org.json4s.jackson.Serialization.read

      val arvosanat = read[Seq[Arvosana]](body)

      arvosanat.length should equal (4)
    }
  }

  test("send arvosana should be successful") {
    implicit val formats = HakurekisteriJsonSupport.format
    val json = write(arvosana12)
    post("/", json, Map("Content-Type" -> "application/json; charset=utf-8")) {
      status should equal (201)
    }
  }

  test("send arvosana should return saved") {
    implicit val formats = HakurekisteriJsonSupport.format
    Seq(arvosana1, arvosana12, arvosana2, arvosana3).foreach(a => {
      post("/", write(a), Map("Content-Type" -> "application/json; charset=utf-8")) {
        val saved = read[Arvosana with Identified[UUID]](body)
        saved.suoritus should equal (a.suoritus)
        saved.arvio should equal (a.arvio)
        saved.aine should equal (a.aine)
        saved.lisatieto should equal (a.lisatieto)
        saved.valinnainen should equal (a.valinnainen)
        saved.myonnetty should equal (a.myonnetty)
        saved.source should equal (a.source)
        saved.jarjestys should equal (a.jarjestys)
      }
    })
  }
}
