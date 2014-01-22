package fi.vm.sade.hakurekisteri.acceptance

import org.scalatra.test.scalatest.ScalatraFeatureSpec
import org.scalatest.GivenWhenThen
import fi.vm.sade.hakurekisteri.{SuoritusActor, Suoritus, SuoritusServlet}
import org.json4s._
import org.json4s.jackson.JsonMethods._
import akka.actor.{Props, ActorSystem}
import java.beans.PropertyChangeSupport
import javax.servlet.Servlet
import javax.servlet.http.HttpServlet
import org.scalatra.test.HttpComponentsClient
import fi.vm.sade.hakurekisteri.acceptance.tools.HakurekisteriSupport

class HaeValmistuvatSpec extends ScalatraFeatureSpec with GivenWhenThen with HakurekisteriSupport {

  info("Peruskoulua päättävänä hakijana")
  info("haluan Oponi löytävän tietoni hakupalvelusta")
  info("jotta saan ohjausta")
  info("ja apua hakuprosessissa")

  feature("Lähtökoulu- ja luokka-tiedot") {
    scenario("Haetaan suoritustietoja henkiloOidilla ja arvioidulla valmistumiskaudella") {
      Given("henkilön suoritus on tietokannassa")
      db has suoritus

      When("haetaan suorituksia")
      val haettu = allSuoritukset

      Then("tiedot löytyvät")
      haettu should contain(suoritus)
    }
  }


}



