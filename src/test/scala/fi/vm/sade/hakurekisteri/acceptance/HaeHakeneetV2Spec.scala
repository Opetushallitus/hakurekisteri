package fi.vm.sade.hakurekisteri.acceptance

import akka.util.Timeout
import fi.vm.sade.hakurekisteri.acceptance.tools.HakeneetSupport
import fi.vm.sade.hakurekisteri.hakija.representation.JSONHakijat
import fi.vm.sade.hakurekisteri.hakija.{HakijaQuery, Hakuehto}
import fi.vm.sade.hakurekisteri.rest.support.{AuditSessionRequest, DefinedRole, Role, User}
import org.scalatest.GivenWhenThen
import org.scalatra.test.scalatest.ScalatraFeatureSpec
import org.springframework.security.cas.authentication.CasAuthenticationToken

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps

class HaeHakeneetV2Spec extends ScalatraFeatureSpec with GivenWhenThen with HakeneetSupport {

  def createTestUser(user: String, organisaatioOids: Set[String]) = new User {
    override val username: String = user
    override val auditSession = AuditSessionRequest(user, organisaatioOids, "", "")
    override def orgsFor(action: String, resource: String): Set[String] = organisaatioOids
    override def casAuthenticationToken: CasAuthenticationToken =
      fi.vm.sade.hakurekisteri.web.rest.support.TestUser.casAuthenticationToken
    override def hasRole(role: Role) = true
  }

  val testUser = createTestUser(
    "testikäyttäjä",
    Set(OpetuspisteX.oid, OpetuspisteY.oid, OpetuspisteZ.oid, "1.2.246.562.10.00000000001")
  )

  info("Koulun virkailijana")
  info("haluan tiedon kouluuni hakeneista oppilaista")
  info("että voin alkaa tekemään valmisteluja tulevaa varten")

  feature("Muodosta hakeneet ja valitut siirtotiedosto") {

    scenario("Opetuspisteeseen X hakijat") {
      Given("N henkilöä täyttää hakemuksen; osa kohdistuu opetuspisteeseen X")
      Hakupalvelu has (FullHakemus1, FullHakemus2)

      When("rajaan muodostusta valitsemalla opetuspisteeseen X")
      val hakijat: JSONHakijat = Await
        .result(
          testHakijaResource.get(
            HakijaQuery(None, Some(OpetuspisteX.oid), None, Hakuehto.Kaikki, Some(testUser), 2)
          ),
          Timeout(60 seconds).duration
        )
        .asInstanceOf[JSONHakijat]

      Then("saan siirtotiedoston, jossa on opetuspisteeseen X tai sen lapsiin hakeneet")
      hakijat.hakijat.size should equal(1)
      hakijat.hakijat.foreach((hakija) => {
        hakija.hakemus.hakutoiveet.head.opetuspiste should equal(OpetuspisteX.toimipistekoodi)
      })
    }

    scenario("Kaikki hakeneet") {
      Given("Kaikkiaan kaksi henkilöä täyttää hakemuksen")
      Hakupalvelu has (FullHakemus1, FullHakemus2)

      When("rajaan muodostusta valitsemalla 'Kaikki hakeneet'")
      val hakijat: JSONHakijat = Await
        .result(
          testHakijaResource.get(HakijaQuery(None, None, None, Hakuehto.Kaikki, Some(testUser), 2)),
          Timeout(60 seconds).duration
        )
        .asInstanceOf[JSONHakijat]

      Then("saan siirtotiedoston, jossa on kaksi hakijaa")
      hakijat.hakijat.size should equal(2)
    }

    scenario("Hyväksytyt hakijat") {
      Given("N henkilöä täyttää hakemuksen")
      Hakupalvelu has (FullHakemus1, FullHakemus2)

      When("rajaan muodostusta valitsemalla 'Hyväksytyt hakijat'")
      val hakijat: JSONHakijat = Await
        .result(
          testHakijaResource.get(
            HakijaQuery(None, None, None, Hakuehto.Hyvaksytyt, Some(testUser), 2)
          ),
          Timeout(60 seconds).duration
        )
        .asInstanceOf[JSONHakijat]

      Then("saan siirtotiedoston, jossa on vain hyväksytyt hakijat")
      hakijat.hakijat.size should equal(1)
    }

    scenario("Paikan vastaanottaneet hakijat") {
      Given("N henkilöä täyttää hakemuksen")
      Hakupalvelu has (FullHakemus1, FullHakemus2)

      When("rajaan muodostusta valitsemalla 'Paikan vastaanottaneet'")
      val hakijat: JSONHakijat = Await
        .result(
          testHakijaResource.get(
            HakijaQuery(None, None, None, Hakuehto.Vastaanottaneet, Some(testUser), 2)
          ),
          Timeout(60 seconds).duration
        )
        .asInstanceOf[JSONHakijat]

      Then("saan siirtotiedoston, jossa on vain paikan vastaanottaneet hakijat")
      hakijat.hakijat.size should equal(1)
    }

    scenario("Vapaaehtoiset uudet tiedot tulostuvat hakemukselle") {
      Given(
        "Henkilö täyttää hakemuksen ja valitsee hakevansa urheilijan ammatilliseen koulutukseen harkinnanvaraisessa sekä valitsee terveys, oikeudenmenetys ja kaksoistutkinto -kysymyksiin kyllä"
      )
      Hakupalvelu has FullHakemus1

      When("haen kaikki hakeneet")
      val hakijat: JSONHakijat = Await
        .result(
          testHakijaResource.get(HakijaQuery(None, None, None, Hakuehto.Kaikki, Some(testUser), 2)),
          Timeout(60 seconds).duration
        )
        .asInstanceOf[JSONHakijat]

      Then("saan siirtotiedoston, jossa on vaaditut arvot")
      hakijat.hakijat.size should equal(1)
      hakijat.hakijat.head.hakemus.hakutoiveet.head.aiempiperuminen should equal(Some(true))
      hakijat.hakijat.head.hakemus.hakutoiveet.head.terveys should equal(Some(true))
      hakijat.hakijat.head.hakemus.hakutoiveet.head.harkinnanvaraisuusperuste should equal(
        Some("2")
      )
      hakijat.hakijat.head.hakemus.hakutoiveet.head.kaksoistutkinto should equal(Some(true))
    }

    scenario("Vain tietyn hakukohteen tiedot") {
      Given("N henkilöä täyttää hakemuksen; osa kohdistuu opetuspisteeseen X")
      Hakupalvelu has (FullHakemus1, FullHakemus2, FullHakemus4)

      When("rajaan muodostusta valitsemalla haun X, opetuspisteeseen X ja Koulutuskoodin X")
      val hakijat: JSONHakijat = Await
        .result(
          testHakijaResource.get(
            HakijaQuery(
              Some("1.1"),
              Some(OpetuspisteX.oid),
              Some("000"),
              Hakuehto.Kaikki,
              Some(testUser),
              2
            )
          ),
          Timeout(60 seconds).duration
        )
        .asInstanceOf[JSONHakijat]

      Then("saan siirtotiedoston, jossa on opetuspisteeseen X tai sen lapsiin hakeneet")
      hakijat.hakijat.size should equal(1)
      hakijat.hakijat.foreach((hakija) => {
        hakija.hakemus.hakutoiveet.head.opetuspiste should equal(OpetuspisteX.toimipistekoodi)
      })

      Then("saan siirtotiedoston, ja hakijan lisäkysymysvastaukset on listattu")
      hakijat.hakijat.size should equal(1)
      hakijat.hakijat.foreach((hakija) => {
        hakija.lisakysymykset.foreach((lisakysymys) =>
          lisakysymys.kysymysid match {
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
          }
        )
      })
    }
  }

  override def stop(): Unit = {
    import scala.concurrent.duration._
    Await.result(system.terminate(), 15.seconds)
    super.stop()
  }
}
