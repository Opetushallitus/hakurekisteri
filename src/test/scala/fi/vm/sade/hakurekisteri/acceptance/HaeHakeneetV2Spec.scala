package fi.vm.sade.hakurekisteri.acceptance

import akka.util.Timeout
import fi.vm.sade.hakurekisteri.acceptance.tools.HakeneetSupport
import fi.vm.sade.hakurekisteri.hakija.representation.JSONHakijat
import fi.vm.sade.hakurekisteri.hakija.{HakijaQuery, Hakuehto}
import fi.vm.sade.hakurekisteri.integration.hakemus.ThemeQuestion
import org.scalatest.GivenWhenThen
import org.scalatra.test.scalatest.ScalatraFeatureSpec

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps

class HaeHakeneetV2Spec extends ScalatraFeatureSpec with GivenWhenThen with HakeneetSupport {

  info("Koulun virkailijana")
  info("haluan tiedon kouluuni hakeneista oppilaista")
  info("että voin alkaa tekemään valmisteluja tulevaa varten")

  feature("Muodosta hakeneet ja valitut siirtotiedosto") {

    scenario("Opetuspisteeseen X hakijat") {
      Given("N henkilöä täyttää hakemuksen; osa kohdistuu opetuspisteeseen X")
      Hakupalvelu has (FullHakemus1, FullHakemus2)

      When("rajaan muodostusta valitsemalla opetuspisteeseen X")
      val hakijat: JSONHakijat = Await.result(testHakijaResource.get(HakijaQuery(None, Some(OpetuspisteX.oid), None, Hakuehto.Kaikki, None, 2)),
        Timeout(60 seconds).duration).asInstanceOf[JSONHakijat]

      Then("saan siirtotiedoston, jossa on opetuspisteeseen X tai sen lapsiin hakeneet")
      hakijat.hakijat.size should equal (1)
      hakijat.hakijat.foreach((hakija) => {
        hakija.hakemus.hakutoiveet.head.opetuspiste should equal (OpetuspisteX.toimipistekoodi)
      })
    }

    scenario("Kaikki hakeneet") {
      Given("Kaikkiaan kaksi henkilöä täyttää hakemuksen")
      Hakupalvelu has (FullHakemus1, FullHakemus2)

      When("rajaan muodostusta valitsemalla 'Kaikki hakeneet'")
      val hakijat: JSONHakijat = Await.result(testHakijaResource.get(HakijaQuery(None, None, None, Hakuehto.Kaikki, None, 2)),
        Timeout(60 seconds).duration).asInstanceOf[JSONHakijat]

      Then("saan siirtotiedoston, jossa on kaksi hakijaa")
      hakijat.hakijat.size should equal (2)
    }

    scenario("Hyväksytyt hakijat") {
      Given("N henkilöä täyttää hakemuksen")
      Hakupalvelu has (FullHakemus1, FullHakemus2)

      When("rajaan muodostusta valitsemalla 'Hyväksytyt hakijat'")
      val hakijat: JSONHakijat = Await.result(testHakijaResource.get(HakijaQuery(None, None, None, Hakuehto.Hyvaksytyt, None, 2)),
        Timeout(60 seconds).duration).asInstanceOf[JSONHakijat]

      Then("saan siirtotiedoston, jossa on vain hyväksytyt hakijat")
      hakijat.hakijat.size should equal (1)
    }

    scenario("Paikan vastaanottaneet hakijat") {
      Given("N henkilöä täyttää hakemuksen")
      Hakupalvelu has (FullHakemus1, FullHakemus2)

      When("rajaan muodostusta valitsemalla 'Paikan vastaanottaneet'")
      val hakijat: JSONHakijat = Await.result(testHakijaResource.get(HakijaQuery(None, None, None, Hakuehto.Vastaanottaneet, None, 2)),
        Timeout(60 seconds).duration).asInstanceOf[JSONHakijat]

      Then("saan siirtotiedoston, jossa on vain paikan vastaanottaneet hakijat")
      hakijat.hakijat.size should equal (1)
    }

    scenario("Vapaaehtoiset uudet tiedot tulostuvat hakemukselle") {
      Given("Henkilö täyttää hakemuksen ja valitsee hakevansa urheilijan ammatilliseen koulutukseen harkinnanvaraisessa sekä valitsee terveys, oikeudenmenetys ja kaksoistutkinto -kysymyksiin kyllä")
      Hakupalvelu has FullHakemus1


      When("haen kaikki hakeneet")
      val hakijat: JSONHakijat = Await.result(testHakijaResource.get(HakijaQuery(None, None, None, Hakuehto.Kaikki, None, 2)),
        Timeout(60 seconds).duration).asInstanceOf[JSONHakijat]

      Then("saan siirtotiedoston, jossa on vaaditut arvot")
      hakijat.hakijat.size should equal(1)
      hakijat.hakijat.head.hakemus.hakutoiveet.head.aiempiperuminen should equal(Some(true))
      hakijat.hakijat.head.hakemus.hakutoiveet.head.terveys should equal(Some(true))
      hakijat.hakijat.head.hakemus.hakutoiveet.head.harkinnanvaraisuusperuste should equal(Some("2"))
      hakijat.hakijat.head.hakemus.hakutoiveet.head.kaksoistutkinto should equal(Some(true))
    }


    scenario("Vain tietyn hakukohteen tiedot") {
      Given("N henkilöä täyttää hakemuksen; osa kohdistuu opetuspisteeseen X")
      Hakupalvelu has(FullHakemus1, FullHakemus2, FullHakemus4)
      Hakupalvelu withLisakysymykset lisakysymysMap

      When("rajaan muodostusta valitsemalla haun X, opetuspisteeseen X ja Koulutuskoodin X")
      val hakijat: JSONHakijat = Await.result(testHakijaResource.get(HakijaQuery(Some("1.1"), Some(OpetuspisteX.oid), Some("000"), Hakuehto.Kaikki, None, 2)),
        Timeout(60 seconds).duration).asInstanceOf[JSONHakijat]

      Then("saan siirtotiedoston, jossa on opetuspisteeseen X tai sen lapsiin hakeneet")
      hakijat.hakijat.size should equal(1)
      hakijat.hakijat.foreach((hakija) => {
        hakija.hakemus.hakutoiveet.head.opetuspiste should equal(OpetuspisteX.toimipistekoodi)
      })

      Then("saan siirtotiedoston, ja hakijan lisäkysymysvastaukset on listattu")
      hakijat.hakijat.size should equal(1)
      hakijat.hakijat.foreach((hakija) => {
        hakija.lisakysymykset.foreach((lisakysymys) => lisakysymys.kysymysid match {
          case "miksi_ammatilliseen" => {
            lisakysymys.kysymysteksti should equal("Miksi haet erityisoppilaitokseen?")
            lisakysymys.kysymystyyppi should equal("ThemeTextQuestion")
            lisakysymys.vastaukset.foreach((vastaus) => {
              vastaus.vastausteksti should equal("Siksi ammatilliseen")
            })
          }
          case "lisakysymys3" => {
            lisakysymys.kysymysteksti should equal("Tekstikysymys")
            lisakysymys.kysymystyyppi should equal("ThemeTextQuestion")
            lisakysymys.vastaukset.foreach((vastaus) => {
              vastaus.vastausteksti should equal("Tekstikysymys")
            })
          }
          case "lisakysymys2" => {
            lisakysymys.kysymysteksti should equal("Checkbox kysymys")
            lisakysymys.kysymystyyppi should equal("ThemeCheckBoxQuestion")
            lisakysymys.vastaukset.foreach((vastaus) => {
              vastaus.vastausid should equal(Some("option_1"))
              vastaus.vastausteksti should equal("Ei2")
            })
          }
          case "lisakysymys1" => {
            lisakysymys.kysymysteksti should equal("Radiobutton kysymys")
            lisakysymys.kysymystyyppi should equal("ThemeRadioButtonQuestion")
            lisakysymys.vastaukset.foreach((vastaus) => {
              vastaus.vastausid should equal(Some("option_0"))
              vastaus.vastausteksti should equal("Kyllä1")
            })
          }
          case _ =>
        })
      })
    }
  }

  def lisakysymysMap: Map[String, ThemeQuestion] = Map(
    "lisakysymys1" -> ThemeQuestion(
      `type`="ThemeRadioButtonQuestion",
      messageText = "Radiobutton kysymys",
      applicationOptionOids = Seq(),
      options = Some(Map(
        "option_0" -> "Kyllä1",
        "option_1" -> "Ei1"
      ))),

    "lisakysymys2" -> ThemeQuestion(
      `type`="ThemeCheckBoxQuestion",
      messageText = "Checkbox kysymys",
      applicationOptionOids = Seq(),
      options = Some(Map(
        "option_0" -> "Kyllä2",
        "option_1" -> "Ei2"
      ))),

    "lisakysymys3" -> ThemeQuestion(
      `type`="ThemeTextQuestion",
      messageText = "Tekstikysymys",
      applicationOptionOids = Seq(),
      options = None),

    "hojks" -> ThemeQuestion(
      isHaunLisakysymys = true,
      `type` = "ThemeRadioButtonQuestion",
      messageText = "Onko sinulle laadittu peruskoulussa tai muita opintoja suorittaessasi HOJKS (Henkilökohtainen opetuksen järjestämistä koskeva " +
        "suunnitelma)?",
      applicationOptionOids = Nil,
      options = Some(Map("true" -> "Kyllä", "false" -> "Ei"))),

    "koulutuskokeilu" -> ThemeQuestion(
      isHaunLisakysymys = true,
      `type` = "ThemeRadioButtonQuestion",
      messageText = "Oletko ollut koulutuskokeilussa?",
      applicationOptionOids = Nil,
      options = Some(Map("true" -> "Kyllä", "false" -> "Ei"))),

    "miksi_ammatilliseen" -> ThemeQuestion(
      isHaunLisakysymys = true,
      `type` = "ThemeTextQuestion",
      messageText = "Miksi haet erityisoppilaitokseen?",
      applicationOptionOids = Nil,
      options = None)
  )



  override def stop(): Unit = {
    import scala.concurrent.duration._
    Await.result(system.terminate(), 15.seconds)
    super.stop()
  }
}
