package fi.vm.sade.hakurekisteri

import org.scalatra.test.scalatest.ScalatraFunSuite
import fi.vm.sade.hakurekisteri.hakija._
import akka.actor.Props
import fi.vm.sade.hakurekisteri.acceptance.tools.HakeneetSupport
import org.scalatra.swagger.Swagger
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriSwagger

class HakijaResourceSpec extends ScalatraFunSuite with HakeneetSupport {
  implicit val swagger: Swagger = new HakurekisteriSwagger

  val orgs = system.actorOf(Props(new MockedOrganisaatioActor()))
  val hakijat = system.actorOf(Props(new HakijaActor(hakupalvelu, orgs, koodisto, sijoittelu)))
  addServlet(new HakijaResource(hakijat), "/")

  test("result is XML") {
    get("/?hakuehto=Kaikki&tyyppi=Xml") {
      body should include ("<?xml")
    }
  }

  test("XML contains root element") {
    get("/?hakuehto=Kaikki&tyyppi=Xml") {
      body should include ("<Hakijat")
    }
  }

  test("result is binary and not empty when asked in Excel") {
    get("/?hakuehto=Kaikki&tyyppi=Excel") {
      body.length should not be 0
      header("Content-Type") should include ("application/octet-stream")
      header("Content-Disposition") should be ("attachment;filename=hakijat.xls")
    }
  }
}
