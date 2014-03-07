package fi.vm.sade.hakurekisteri.acceptance

import org.scalatra.test.scalatest.ScalatraFeatureSpec
import org.scalatest.GivenWhenThen

class HaeHakeneetSpec extends ScalatraFeatureSpec with GivenWhenThen {


  info("Koulun virkailijana")
  info("haluan tiedon kouluuni hakeneista oppilaista")
  info("että voin alkaa tekemään valmisteluja tulevaa varten")


  feature("Muodosta hakeneet ja valitut siirtotiedosto") {

    scenario("Organisaation X hakijat") {
      Given("N henkilöä täyttää hakemuksen; osa kohdistuu organisaatioon X tai sen lapsiin")
      When("rajaan muodostusta valitsemalla organisaation X")
      Then("saan siirtotiedoston, jossa on organisaatioon X tai sen lapsiin hakeneet")
    }

    scenario("Haussa Y hakeneet") {
      Given("N henkilöä täyttää hakemuksen; viisi kohdistuu hakuun Y")
      When("rajaan muodostusta valitsemalla haun Y")
      Then("saan siirtotiedoston, jossa on kyseiset viisi hakijaa")
    }

    scenario("Hakukohdekoodi") {
      Given("N henkilöä täyttää hakemuksen; osa kohdistuu hakukohteisiin tyyppiä Z")
      When("rajaan muodostusta syöttämällä hakukohdekoodin Z")
      Then("saan siirtotiedoston, jossa on hakijat hakukohteisiin tyyppiä Z")
    }

    scenario("XML tiedosto") {
      Given("N henkilöä täyttää hakemuksen")
      When("rajaan muodostusta valitsemalla tiedostotyypiksi 'XML'")
      Then("saan siirtotiedoston, joka on XML-muodossa")
    }

    scenario("Excel tiedosto") {
      Given("N henkilöä täyttää hakemuksen")
      When("rajaan muodostusta valitsemalla tiedostotyypiksi 'Excel'")
      Then("saan siirtotiedoston, joka on Excel-muodossa")
    }





    // Myöhemmin nämä

    scenario("Kaikki hakeneet") {
      Given("Kaikkiaan viisi henkilöä täyttää hakemuksen")
      When("rajaan muodostusta valitsemalla 'Kaikki hakeneet'")
      Then("saan siirtotiedoston, jossa on kaikki viisi hakijaa")
    }

    scenario("Hyväksytyt hakijat") {
      Given("N henkilöä täyttää hakemuksen")
      When("rajaan muodostusta valitsemalla 'Hyväksytyt hakijat'")
      Then("saan siirtotiedoston, jossa on vain hyväksytyt hakijat")
    }

    scenario("Paikan vastaanottaneet hakijat") {
      Given("N henkilöä täyttää hakemuksen")
      When("rajaan muodostusta valitsemalla 'Paikan vastaanottaneet'")
      Then("saan siirtotiedoston, jossa on vain paikan vastaanottaneet hakijat")
    }

  }
}
