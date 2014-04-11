package fi.vm.sade.hakurekisteri

import org.scalatra.test.scalatest.ScalatraFunSuite
import fi.vm.sade.hakurekisteri.hakija._
import akka.actor.{ActorSystem, Props}
import fi.vm.sade.hakurekisteri.acceptance.tools.HakeneetSupport
import org.scalatra.swagger.Swagger
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriSwagger

class HakijaResourceSpec extends ScalatraFunSuite with HakeneetSupport {
  implicit val swagger: Swagger = new HakurekisteriSwagger

  val hakijat = system.actorOf(Props(new HakijaActor(hakupalvelu, organisaatiopalvelu, koodistopalvelu)))
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
}
