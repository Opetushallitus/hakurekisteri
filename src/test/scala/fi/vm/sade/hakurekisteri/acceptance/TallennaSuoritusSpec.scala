package fi.vm.sade.hakurekisteri.acceptance

import org.scalatra.test.scalatest.ScalatraFeatureSpec
import org.scalatest.GivenWhenThen

import org.json4s._
import org.json4s.jackson.Serialization
import org.json4s.jackson.Serialization.{read, write}
import org.json4s.jackson.JsonMethods._
import fi.vm.sade.hakurekisteri.{SuoritusActor, SuoritusServlet, Suoritus}
import akka.actor.{Props, ActorSystem}
import fi.vm.sade.hakurekisteri.acceptance.tools.HakurekisteriSupport
import java.util.Locale

class TallennaSuoritusSpec extends ScalatraFeatureSpec with GivenWhenThen with HakurekisteriSupport {

  info("Koulun virkailijana")
  info("tallennan kouluni oppilaiden tutkintosuoritukset")
  info("jotta niitä voi hyödyntää haussa")
  info("ja valinnassa")

  feature("Suorituksen tallentaminen") {
    scenario("Tallennetaan suoritus tyhjään kantaan") {
      Given("kanta on tyhjä")
      db is empty

      When("suoritus luodaan järjestelmään")

      create(suoritus)

      Then("löytyy kannasta ainoastaan tallennettu suoritus")
      allSuoritukset should equal(Seq(suoritus))
    }

    scenario("Tallennettaan kantaan jossa on tietoa") {
      Given("kannassa on suorituksia")
      db has (suoritus, suoritus2)

      When("uusi suoritus luodaan järjestelmään")

      create(suoritus3)

      Then("löytyy kannasta  tallennettu suoritus")
      allSuoritukset should contain(suoritus3)
    }

    scenario("Vanhat tiedot säilyvät") {
      Given("kannassa on suorituksia")
      db has (suoritus, suoritus2)

      When("uusi suoritus luodaan järjestelmään")

      create(suoritus3)

      Then("löytyy kannasta  tallennettu suoritus")
      allSuoritukset should (contain(suoritus)  and contain(suoritus2))
    }

  }


}
