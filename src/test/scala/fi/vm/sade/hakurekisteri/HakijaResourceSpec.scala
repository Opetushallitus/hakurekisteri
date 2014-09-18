package fi.vm.sade.hakurekisteri

import fi.vm.sade.hakurekisteri.integration.organisaatio.OrganisaatioActor
import fi.vm.sade.hakurekisteri.integration.sijoittelu.SijoitteluActor
import org.scalatra.test.scalatest.ScalatraFunSuite
import fi.vm.sade.hakurekisteri.hakija._
import akka.actor.Props
import fi.vm.sade.hakurekisteri.acceptance.tools.HakeneetSupport
import org.scalatra.swagger.Swagger
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriSwagger
import org.specs.specification.Examples

class HakijaResourceSpec extends ScalatraFunSuite with HakeneetSupport {
  implicit val swagger: Swagger = new HakurekisteriSwagger

  val orgs = system.actorOf(Props(new MockedOrganisaatioActor()))
  val sijoittelu = system.actorOf(Props(new SijoitteluActor(sijoitteluClient)))
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
}
