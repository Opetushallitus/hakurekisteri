package fi.vm.sade.hakurekisteri.rest

import akka.actor.Props
import fi.vm.sade.hakurekisteri.MockConfig
import fi.vm.sade.hakurekisteri.acceptance.tools.HakeneetSupport
import fi.vm.sade.hakurekisteri.hakija._
import fi.vm.sade.hakurekisteri.integration.LocalhostProperties
import fi.vm.sade.hakurekisteri.web.hakija.HakijaResource
import fi.vm.sade.hakurekisteri.web.rest.support.{HakurekisteriSwagger, TestSecurity}
import org.scalatra.swagger.Swagger
import org.scalatra.test.scalatest.ScalatraFunSuite

import scala.concurrent.Await

class HakijaResourceSpec extends ScalatraFunSuite with HakeneetSupport with LocalhostProperties {
  implicit val swagger: Swagger = new HakurekisteriSwagger
  implicit val security = new TestSecurity
  val hakijat = system.actorOf(Props(new HakijaActor(Hakupalvelu, organisaatioActor, koodistoActor, sijoittelu, new MockConfig)))
  addServlet(new HakijaResource(hakijat), "/")

  test("fails with bad request if there is no query parameter") {
    get("/") {
      status should be (400)
      body should include ("pakolliset parametrit puuttuvat")
    }
  }

  test("fails with bad request if there is no organisaatio query parameter") {
    get("/?haku=dummy") {
      status should be (400)
      body should include ("pakolliset parametrit puuttuvat")
    }
  }

  test("fails with bad request if there is no haku query parameter") {
    get("/?organisaatio=dummy") {
      status should be (400)
      body should include ("pakolliset parametrit puuttuvat")
    }
  }

  test("result is XML") {
    get("/?hakuehto=Kaikki&tyyppi=Xml&haku=dummy&organisaatio=dummy") {
      body should include ("<?xml")
    }
  }

  test("XML contains root element") {
    get("/?hakuehto=Kaikki&tyyppi=Xml&haku=dummy&organisaatio=dummy") {
      body should include ("<Hakijat")
    }
  }

  test("JSON contains postoffice") {
    Hakupalvelu has (FullHakemus1)
    get("/?hakuehto=Kaikki&tyyppi=Json&haku=dummy&organisaatio=1.10.3") {
      body should include ("\"postitoimipaikka\":\"Posti_00100\"")
    }
  }

  test("JSON contains foreign postoffice") {
    Hakupalvelu has (FullHakemus3)
    get("/?hakuehto=Kaikki&tyyppi=Json&haku=dummy&organisaatio=1.10.5") {
      body should include ("\"postitoimipaikka\":\"Parc la Vuori\"")
    }
  }

  test("result is binary and not empty when asked in Excel") {
    get("/?hakuehto=Kaikki&tyyppi=Excel&haku=dummy&organisaatio=dummy") {
      body.length should not be 0
      header("Content-Type") should include ("application/octet-stream")
      header("Content-Disposition") should be ("attachment;filename=hakijat.xlsx")
    }
  }

  override def stop(): Unit = {
    import scala.concurrent.duration._
    Await.result(system.terminate(), 15.seconds)
    super.stop()
  }

}
