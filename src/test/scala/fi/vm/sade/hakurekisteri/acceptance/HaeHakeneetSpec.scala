package fi.vm.sade.hakurekisteri.acceptance

import org.scalatra.test.scalatest.ScalatraFeatureSpec
import org.scalatest.GivenWhenThen
import fi.vm.sade.hakurekisteri.acceptance.tools.HakeneetSupport
import fi.vm.sade.hakurekisteri.hakija.{XMLHakijat, Tyyppi, Hakuehto, HakijaQuery}
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
      hakupalvelu has (FullHakemus1, FullHakemus2)

      When("rajaan muodostusta valitsemalla opetuspisteeseen X")
      val hakijat: XMLHakijat = Await.result(hakijaResource.get(HakijaQuery(None, Some(OpetuspisteX.oid), None, Hakuehto.Kaikki, None)),
        Timeout(60 seconds).duration).asInstanceOf[XMLHakijat]
      println("tiedosto: " + hakijat)

      Then("saan siirtotiedoston, jossa on opetuspisteeseen X tai sen lapsiin hakeneet")
      hakijat.hakijat.size should equal (1)
      hakijat.hakijat.foreach((hakija) => {
        hakija.hakemus.hakutoiveet.head.opetuspiste should equal (OpetuspisteX.toimipistekoodi)
      })
    }

    scenario("XML tiedosto") {
      Given("N henkilöä täyttää hakemuksen")
      hakupalvelu has (FullHakemus1)

      When("rajaan muodostusta valitsemalla tiedostotyypiksi 'XML'")
      val hakijat: XMLHakijat = Await.result(hakijaResource.get(HakijaQuery(None, Some(OpetuspisteX.oid), None, Hakuehto.Kaikki, None)),
        Timeout(60 seconds).duration).asInstanceOf[XMLHakijat]
      println("tiedosto: " + hakijat)

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

    scenario("Kaikki hakeneet") {
      Given("Kaikkiaan kaksi henkilöä täyttää hakemuksen")
      hakupalvelu has (FullHakemus1, FullHakemus2)

      When("rajaan muodostusta valitsemalla 'Kaikki hakeneet'")
      val hakijat: XMLHakijat = Await.result(hakijaResource.get(HakijaQuery(None, None, None, Hakuehto.Kaikki, None)),
        Timeout(60 seconds).duration).asInstanceOf[XMLHakijat]
      println("hyväksytyt: " + hakijat)

      Then("saan siirtotiedoston, jossa on kaksi hakijaa")
      hakijat.hakijat.size should equal (2)
    }

    scenario("Hyväksytyt hakijat") {
      Given("N henkilöä täyttää hakemuksen")
      hakupalvelu has (FullHakemus1, FullHakemus2)

      When("rajaan muodostusta valitsemalla 'Hyväksytyt hakijat'")
      val hakijat: XMLHakijat = Await.result(hakijaResource.get(HakijaQuery(None, None, None, Hakuehto.Hyväksytyt, None)),
        Timeout(60 seconds).duration).asInstanceOf[XMLHakijat]
      println("hyväksytyt: " + hakijat)

      Then("saan siirtotiedoston, jossa on vain hyväksytyt hakijat")
      hakijat.hakijat.size should equal (0)
    }

    scenario("Paikan vastaanottaneet hakijat") {
      Given("N henkilöä täyttää hakemuksen")
      hakupalvelu has (FullHakemus1, FullHakemus2)

      When("rajaan muodostusta valitsemalla 'Paikan vastaanottaneet'")
      val hakijat: XMLHakijat = Await.result(hakijaResource.get(HakijaQuery(None, None, None, Hakuehto.Vastaanottaneet, None)),
        Timeout(60 seconds).duration).asInstanceOf[XMLHakijat]
      println("vastaanottaneet: " + hakijat)

      Then("saan siirtotiedoston, jossa on vain paikan vastaanottaneet hakijat")
      hakijat.hakijat.size should equal (0)
    }

  }
}
