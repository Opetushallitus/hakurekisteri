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
import fi.vm.sade.hakurekisteri.acceptance.tools.{kausi, HakurekisteriSupport}
import java.util.Date
import kausi._
import java.text.SimpleDateFormat
import org.scalatest.matchers.Matcher

class HaeValmistuvatSpec extends ScalatraFeatureSpec with GivenWhenThen with HakurekisteriSupport {

  info("Peruskoulua päättävänä hakijana")
  info("haluan Oponi löytävän tietoni hakupalvelusta")
  info("jotta saan ohjausta")
  info("ja apua hakuprosessissa")

  feature("Lähtökoulu- ja luokka-tiedot") {
    scenario("Yksi valmistuminen") {
      Given("Kaksi henkilöä valmistuu keväällä")
      Mikko valmistuu (Keväällä, 2014) koulusta "1.2.3"
      Matti valmistuu (Keväällä, 2014) koulusta "1.2.4"


      When("haetaan suorituksia toiselle henkilölle keväältä")
      val haettu = hae(
        suoritukset
        henkilolle Mikko
        vuodelta 2014
        kaudelta Kevät
      )

      Then("saan hakutuloksen jossa on vain haetun henkilön suoritus")
      haettu.length should equal (1)
      haettu.head.henkiloOid should equal (Mikko.oid)
      haettu.head.opilaitosOid should equal ("1.2.3")
      haettu.head.arvioituValmistuminen should beBefore ("01.07.2014")

    }

    scenario("Valmistuu kahdesti") {
      Given("henkilö valmistuu keväällä ja syksyllä")
      Mikko valmistuu (Keväällä, 2014) koulusta "1.2.3"
      Mikko valmistuu (Syksyllä, 2014) koulusta "1.2.4"


      When("haetaan suorituksia toiselle henkilölle keväältä")
      val haettu = hae(
        suoritukset
          henkilolle Mikko
          vuodelta 2014
          kaudelta Syksy
      )

      Then("saan hakutuloksen jossa on vain haetun henkilön suoritus")
      haettu.length should equal (1)
      haettu.head.henkiloOid should equal (Mikko.oid)
      haettu.head.opilaitosOid should equal ("1.2.4")
      haettu.head.arvioituValmistuminen should not(beBefore ("01.07.2014"))

    }


  }







}



