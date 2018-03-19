package fi.vm.sade.hakurekisteri.rest

import akka.actor.Props
import fi.vm.sade.hakurekisteri.acceptance.tools.HakeneetSupport
import fi.vm.sade.hakurekisteri.hakija._
import fi.vm.sade.hakurekisteri.integration.LocalhostProperties
import fi.vm.sade.hakurekisteri.web.hakija.HakijaResourceV2
import fi.vm.sade.hakurekisteri.web.rest.support.{HakurekisteriSwagger, TestSecurity}
import org.scalatra.swagger.Swagger
import org.scalatra.test.scalatest.ScalatraFunSuite

import scala.concurrent.Await

class HakijaResourceSpecV2 extends ScalatraFunSuite with HakeneetSupport with LocalhostProperties {
  implicit val swagger: Swagger = new HakurekisteriSwagger
  implicit val security = new TestSecurity
  val hakijat = system.actorOf(Props(new HakijaActor(Hakupalvelu, organisaatioActor, koodistoActor, sijoittelu, valintaTulosTimeout)))
  addServlet(new HakijaResourceV2(hakijat), "/")

  test("XML is not supported anymore") {
    get("/?haku=1&hakuehto=Kaikki&tyyppi=Xml") {
      body should include("tyyppi Xml is not supported")
    }
  }

  test("Haku oid must be given") {
    get("/?hakuehto=Kaikki&tyyppi=Json") {
      body should include("Haku can not be empty")
    }
  }

  test("JSON contains postoffice") {
    Hakupalvelu has FullHakemus1
    get("/?haku=1&hakuehto=Kaikki&tyyppi=Json") {
      body should include("\"postitoimipaikka\":\"Posti_00100\"")
    }
  }

  test("JSON contains foreign postoffice") {
    Hakupalvelu has FullHakemus3
    get("/?haku=1&hakuehto=Kaikki&tyyppi=Json") {
      body should include("\"postitoimipaikka\":\"Parc la Vuori\"")
    }
  }

  test("JSON contains foreign huoltajan nimi") {
    Hakupalvelu has FullHakemus3
    get("/?haku=1&hakuehto=Kaikki&tyyppi=Json") {
      body should include("\"huoltajannimi\":\"huoltajannimi\"")
    }
  }

  test("result is binary and not empty when asked in Excel") {
    get("/?haku=1&hakuehto=Kaikki&tyyppi=Excel") {
      body.length should not be 0
      header("Content-Type") should include("application/octet-stream")
      header("Content-Disposition") should be("attachment;filename=hakijat.xls")
    }
  }

  override def stop(): Unit = {
    import scala.concurrent.duration._
    Await.result(system.terminate(), 15.seconds)
    super.stop()
  }

}
