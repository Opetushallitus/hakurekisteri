package fi.vm.sade.hakurekisteri.rest

import java.util.UUID

import akka.actor.{ActorSystem, Props}
import fi.vm.sade.hakurekisteri.acceptance.tools.FakeAuthorizer
import fi.vm.sade.hakurekisteri.arvosana._
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.storage.repository.{InMemJournal, Updated}
import fi.vm.sade.hakurekisteri.web.arvosana.{ArvosanaSwaggerApi, CreateArvosanaCommand}
import fi.vm.sade.hakurekisteri.web.rest.support._
import org.joda.time.LocalDate
import org.json4s.jackson.Serialization._
import org.scalatra.test.scalatest.ScalatraFunSuite

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.implicitConversions

class ArvosanaSerializeSpec extends ScalatraFunSuite {
  val suoritus = UUID.randomUUID()
  val arvosana1 = Arvosana(suoritus, Arvio410("10"), "AI", Some("FI"), valinnainen = false, None, "Test", Map())
  val arvosana12 = Arvosana(suoritus,Arvio410("10"), "AI", Some("FI"), valinnainen = true, None, "Test", Map(), Some(0))
  val arvosana2 = Arvosana(UUID.randomUUID(), ArvioYo("L", Some(100)), "AI", Some("FI"), valinnainen = false, Some(new LocalDate()), "Test", Map())
  val arvosana3 = Arvosana(UUID.randomUUID(), ArvioOsakoe("10"), "AI", Some("FI"), valinnainen = false, Some(new LocalDate()), "Test", Map())

  implicit val system = ActorSystem()
  implicit val security = new TestSecurity
  implicit def seq2journal[R <: fi.vm.sade.hakurekisteri.rest.support.Resource[UUID, R]](s:Seq[R]): InMemJournal[R, UUID] = {
    val journal = new InMemJournal[R, UUID]
    s.foreach((resource:R) => journal.addModification(Updated(resource.identify(UUID.randomUUID()))))
    journal
  }
  val arvosanaRekisteri = system.actorOf(Props(new ArvosanaActor(Seq(arvosana1, arvosana12, arvosana2, arvosana3))))
  val guardedArvosanaRekisteri = system.actorOf(Props(new FakeAuthorizer(arvosanaRekisteri)))
  implicit val swagger = new HakurekisteriSwagger

  addServlet(new HakurekisteriResource[Arvosana, CreateArvosanaCommand](guardedArvosanaRekisteri, ArvosanaQuery(_)) with ArvosanaSwaggerApi with HakurekisteriCrudCommands[Arvosana, CreateArvosanaCommand], "/*")



  test("query should return 200") {
    get(s"/?suoritus=${suoritus.toString}") {
      status should equal (200)
    }
  }

  test("body should contain a sequence with two arvosanas") {
    get(s"/?suoritus=${suoritus.toString}") {
      implicit val formats = HakurekisteriJsonSupport.format
      import org.json4s.jackson.Serialization.read

      val arvosanat = read[Seq[Arvosana]](body)

      arvosanat.length should equal (2)
    }
  }

  test("empty query should return 400") {
    get("/") {
      status should be (400)
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

  test("send YO arvosana without myonnetty should return bad request") {
    val json = "{\"suoritus\":\"" + UUID.randomUUID().toString + "\",\"arvio\":{\"asteikko\":\"YO\",\"arvosana\":\"L\"},\"aine\":\"MA\",\"valinnainen\":false}"
    post("/", json, Map("Content-Type" -> "application/json; charset=utf-8")) {
      status should be (400)
      body should include ("myonnetty is required for asteikko YO and OSAKOE")
    }
  }

  test("send valinnainen 4-10 arvosana without jarjestys should return bad request") {
    val json = "{\"suoritus\":\"" + UUID.randomUUID().toString + "\",\"arvio\":{\"asteikko\":\"4-10\",\"arvosana\":\"10\"},\"aine\":\"MA\",\"valinnainen\":true}"
    post("/", json, Map("Content-Type" -> "application/json; charset=utf-8")) {
      status should be (400)
      body should include ("jarjestys is required for valinnainen arvosana")
    }
  }

  override def stop(): Unit = {
    Await.result(system.terminate(), 15.seconds)
  }
}
