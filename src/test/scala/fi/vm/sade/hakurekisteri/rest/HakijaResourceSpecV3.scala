package fi.vm.sade.hakurekisteri.rest

import akka.actor.Props
import fi.vm.sade.hakurekisteri.acceptance.tools.HakeneetSupport
import fi.vm.sade.hakurekisteri.hakija._
import fi.vm.sade.hakurekisteri.integration.LocalhostProperties
import fi.vm.sade.hakurekisteri.web.hakija.HakijaResourceV3
import fi.vm.sade.hakurekisteri.web.rest.support.{HakurekisteriSwagger, TestSecurity}
import org.scalatra.swagger.Swagger
import org.scalatra.test.scalatest.ScalatraFunSuite

import scala.concurrent.Await

class HakijaResourceSpecV3 extends ScalatraFunSuite with HakeneetSupport with LocalhostProperties {
  implicit val swagger: Swagger = new HakurekisteriSwagger
  implicit val security = new TestSecurity
  val hakijat = system.actorOf(Props(new HakijaActor(Hakupalvelu, organisaatioActor, koodistoActor, sijoittelu)))
  addServlet(new HakijaResourceV3(hakijat), "/")

  test("JSON contains osaaminen yleinen_kielitutkinto_fi and valtionhallinnon_kielitutkinto_fi") {
    Hakupalvelu has FullHakemus1
    get("/?haku=1&hakuehto=Kaikki&tyyppi=Json") {
      body should include("\"yleinen_kielitutkinto_fi\":\"true\"")
      body should include("\"valtionhallinnon_kielitutkinto_fi\":\"true\"")
      body should include("\"koulutuksenKieli\":\"FI\"")
    }
  }

  test("JSON contains foreign huoltajan nimi") {
    Hakupalvelu has FullHakemus5
    get("/?haku=1&hakuehto=Hyvaksytyt&tyyppi=Json") {
      body should include("\"sukunimi\":\"Hyvaksytty\"")
    }
  }

  override def stop(): Unit = {
    import scala.concurrent.duration._
    Await.result(system.terminate(), 15.seconds)
    super.stop()
  }

}
