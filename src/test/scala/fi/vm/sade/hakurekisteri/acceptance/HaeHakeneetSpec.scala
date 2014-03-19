package fi.vm.sade.hakurekisteri.acceptance

import org.scalatra.test.scalatest.ScalatraFeatureSpec
import org.scalatest.GivenWhenThen
import fi.vm.sade.hakurekisteri.acceptance.tools.HakeneetSupport
import fi.vm.sade.hakurekisteri.hakija.{Hakijat, Tyyppi, Hakuehto, HakijaQuery}

class HaeHakeneetSpec extends ScalatraFeatureSpec with GivenWhenThen with HakeneetSupport {


  info("Koulun virkailijana")
  info("haluan tiedon kouluuni hakeneista oppilaista")
  info("että voin alkaa tekemään valmisteluja tulevaa varten")


  feature("Muodosta hakeneet ja valitut siirtotiedosto") {

    scenario("Organisaation X hakijat") {
      Given("N henkilöä täyttää hakemuksen; osa kohdistuu organisaatioon X tai sen lapsiin")
      hakupalvelu has (Hakemus1, Hakemus2)

      When("rajaan muodostusta valitsemalla organisaation X")
      val tiedosto = hakijaResource.get(HakijaQuery(None, Some(OrganisaatioX.oid), None, Hakuehto.Kaikki, Tyyppi.Json)).mapTo[Hakijat]

      Then("saan siirtotiedoston, jossa on organisaatioon X tai sen lapsiin hakeneet")
      //tiedosto sisältää organisaatioon X hakeneet
    }

    scenario("Haussa Y hakeneet") {
      Given("N henkilöä täyttää hakemuksen; yksi kohdistuu hakuun Y")
      //Mikko täyttää hakemuksen yhteishaussa
      //Matti täyttää hakemuksen lisähaussa

      When("rajaan muodostusta valitsemalla haun Y")
      //tiedosto = muodosta(haku = yhteishaku)

      Then("saan siirtotiedoston, jossa on kyseinen hakija")
      //tiedosto sisältää Mikon tiedot
    }

    scenario("Hakukohdekoodi") {
      Given("N henkilöä täyttää hakemuksen; osa kohdistuu hakukohteisiin tyyppiä Z")
      //Mikko täyttää hakemuksen hakukohteeseen 123
      //Matti täyttää hakemuksen hakukohteeseen 190

      When("rajaan muodostusta syöttämällä hakukohdekoodin Z")
      //tiedosto = muodosta(hakukohdekoodi = 123)

      Then("saan siirtotiedoston, jossa on hakijat hakukohteisiin tyyppiä Z")
      //tiedosto sisältää Mikon tiedot
    }

    scenario("XML tiedosto") {
      Given("N henkilöä täyttää hakemuksen")
      //Mikko täyttää hakemuksen

      When("rajaan muodostusta valitsemalla tiedostotyypiksi 'XML'")
      //tiedosto = muodosta(muoto = XML)

      Then("saan siirtotiedoston, joka on XML-muodossa")
      //tiedosto on XML-muodossa
    }

    scenario("Excel tiedosto") {
      Given("N henkilöä täyttää hakemuksen")
      //Mikko täyttää hakemuksen

      When("rajaan muodostusta valitsemalla tiedostotyypiksi 'Excel'")
      //tiedosto = muodosta(muoto = Excel)

      Then("saan siirtotiedoston, joka on Excel-muodossa")
      //tiedosto on Excel-muodossa
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
