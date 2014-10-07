package fi.vm.sade.hakurekisteri.acceptance

import org.scalatra.test.scalatest.ScalatraFeatureSpec
import org.scalatest.GivenWhenThen
import fi.vm.sade.hakurekisteri.acceptance.tools.{kausi, HakurekisteriSupport}
import kausi._
import fi.vm.sade.hakurekisteri.suoritus.VirallinenSuoritus


class HaeValmistuvatSpec extends ScalatraFeatureSpec with GivenWhenThen with HakurekisteriSupport {




  info("Peruskoulua päättävänä hakijana")
  info("haluan Oponi löytävän tietoni hakupalvelusta")
  info("jotta saan ohjausta")
  info("ja apua hakuprosessissa")

  feature("Lähtökoulu- ja luokka-tiedot") {
    scenario("Keväällä valmistuminen") {
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
      haettu.head.asInstanceOf[VirallinenSuoritus].myontaja should equal ("1.2.3")
      haettu.head.asInstanceOf[VirallinenSuoritus].valmistuminen should not(beBefore("01.01.2014"))
      haettu.head.asInstanceOf[VirallinenSuoritus].valmistuminen should beBefore ("01.08.2014")

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
      haettu.head.asInstanceOf[VirallinenSuoritus].myontaja should equal ("1.2.4")
      haettu.head.asInstanceOf[VirallinenSuoritus].valmistuminen should not(beBefore ("01.08.2014"))
      haettu.head.asInstanceOf[VirallinenSuoritus].valmistuminen should beBefore ("31.12.2014")

    }


  }







}



