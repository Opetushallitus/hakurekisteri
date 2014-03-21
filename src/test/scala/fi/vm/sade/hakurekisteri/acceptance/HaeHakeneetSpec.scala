package fi.vm.sade.hakurekisteri.acceptance

import org.scalatra.test.scalatest.ScalatraFeatureSpec
import org.scalatest.GivenWhenThen
import fi.vm.sade.hakurekisteri.acceptance.tools.HakeneetSupport
import fi.vm.sade.hakurekisteri.hakija.{Hakijat, Tyyppi, Hakuehto, HakijaQuery}
import scala.concurrent.{Future, Await}
import scala.concurrent.duration._
import akka.util.Timeout

class HaeHakeneetSpec extends ScalatraFeatureSpec with GivenWhenThen with HakeneetSupport {


  info("Koulun virkailijana")
  info("haluan tiedon kouluuni hakeneista oppilaista")
  info("että voin alkaa tekemään valmisteluja tulevaa varten")


  feature("Muodosta hakeneet ja valitut siirtotiedosto") {

    scenario("Opetuspisteeseen X hakijat") {
      Given("N henkilöä täyttää hakemuksen; osa kohdistuu opetuspisteeseen X")
      hakupalvelu has (FullHakemus1.toSmallHakemus, FullHakemus2.toSmallHakemus)

      When("rajaan muodostusta valitsemalla opetuspisteeseen X")
      val future: Future[Any] = hakijaResource.get(HakijaQuery(None, Some(OpetuspisteX.oid), None, Hakuehto.Kaikki, Tyyppi.Json))
      val foo = Await.result(future, Timeout(60 seconds).duration)
      println(foo)
      val hakijat: Hakijat = foo.asInstanceOf[Hakijat]
      println("tiedosto: " + hakijat)

      Then("saan siirtotiedoston, jossa on opetuspisteeseen X tai sen lapsiin hakeneet")
      hakijat.hakijat.foreach((hakija) => {
        hakija.hakemus.hakutoiveet.head.opetuspiste.get should equal (OpetuspisteX.toimipistekoodi)
      })
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
