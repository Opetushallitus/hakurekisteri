package fi.vm.sade.hakurekisteri.rest

import java.util.UUID

import akka.actor.{ActorSystem, Props}
import fi.vm.sade.hakurekisteri.acceptance.tools.FakeAuthorizer
import fi.vm.sade.hakurekisteri.opiskelija.{Opiskelija, OpiskelijaActor, OpiskelijaQuery}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.hakurekisteri.storage.repository.{InMemJournal, Updated}
import fi.vm.sade.hakurekisteri.web.opiskelija.{CreateOpiskelijaCommand, OpiskelijaSwaggerApi}
import fi.vm.sade.hakurekisteri.web.rest.support.{HakurekisteriCrudCommands, HakurekisteriResource, HakurekisteriSwagger, TestSecurity}
import org.joda.time.DateTime
import org.json4s.jackson.Serialization._
import org.scalatra.test.scalatest.ScalatraFunSuite

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.implicitConversions


class OpiskelijaSpec extends ScalatraFunSuite {
  val now = new DateTime()
  val opiskelija = Opiskelija("1.10.1", "9", "9A", "1.24.1", now, Some(now), "test")

  implicit val system = ActorSystem()
  implicit val security = new TestSecurity
  implicit def seq2journal[R <: fi.vm.sade.hakurekisteri.rest.support.Resource[UUID, R]](s:Seq[R]): InMemJournal[R, UUID] = {
    val journal = new InMemJournal[R, UUID]
    s.foreach((resource:R) => journal.addModification(Updated(resource.identify(UUID.randomUUID()))))
    journal
  }
  val opiskelijaRekisteri = system.actorOf(Props(new OpiskelijaActor(Seq(opiskelija))))
  val guardedOpiskelijaRekisteri = system.actorOf(Props(new FakeAuthorizer(opiskelijaRekisteri)))
  implicit val swagger = new HakurekisteriSwagger

  addServlet(new HakurekisteriResource[Opiskelija, CreateOpiskelijaCommand](guardedOpiskelijaRekisteri, OpiskelijaQuery(_)) with OpiskelijaSwaggerApi with HakurekisteriCrudCommands[Opiskelija, CreateOpiskelijaCommand], "/*")

  test("send opiskelija should return 201") {
    implicit val formats = HakurekisteriJsonSupport.format
    post("/", write(opiskelija), Map("Content-Type" -> "application/json; charset=utf-8")) {
      status should be (201)
    }
  }

  test("send opiskelija with invalid loppuPaiva should return 400") {
    val json = "{\"oppilaitosOid\":\"1.10.1\",\"luokkataso\":\"9\",\"luokka\":\"9A\",\"henkiloOid\":\"1.24.1\",\"alkuPaiva\":\"2015-01-01T00:00:00.000Z\",\"loppuPaiva\":\"2014-12-31T00:00:00.000Z\"}"
    post("/", json, Map("Content-Type" -> "application/json; charset=utf-8")) {
      status should be (400)
      body should include ("loppuPaiva must be after alkuPaiva")
    }
  }

  override def stop(): Unit = {
    Await.result(system.terminate(), 15.seconds)
  }
}
