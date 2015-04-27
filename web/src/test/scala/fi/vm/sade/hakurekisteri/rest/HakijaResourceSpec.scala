package fi.vm.sade.hakurekisteri.rest

import akka.actor.Props
import fi.vm.sade.hakurekisteri.acceptance.tools.HakeneetSupport
import fi.vm.sade.hakurekisteri.hakija._
import fi.vm.sade.hakurekisteri.web.hakija.HakijaResource
import fi.vm.sade.hakurekisteri.web.rest.support.{HakurekisteriSwagger, TestSecurity}
import org.scalatra.swagger.Swagger
import org.scalatra.test.scalatest.ScalatraFunSuite

class HakijaResourceSpec extends ScalatraFunSuite with HakeneetSupport {
  implicit val swagger: Swagger = new HakurekisteriSwagger
  implicit val security = new TestSecurity
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

  override def stop(): Unit = {
    import scala.concurrent.duration._
    system.shutdown()
    system.awaitTermination(15.seconds)
    super.stop()
  }
}
