package fi.vm.sade.hakurekisteri.acceptance

import org.scalatra.test.scalatest.ScalatraFeatureSpec
import org.scalatest.GivenWhenThen

import org.json4s._
import org.json4s.jackson.Serialization
import org.json4s.jackson.Serialization.{read, write}
import org.json4s.jackson.JsonMethods._
import fi.vm.sade.hakurekisteri.{SuoritusActor, SuoritusServlet, Suoritus}
import akka.actor.{Props, ActorSystem}

class TallennaSuoritusSpec extends ScalatraFeatureSpec with GivenWhenThen {
  protected implicit val jsonFormats: Formats = DefaultFormats
  info("Koulun virkailijana")
  info("tallennan kouluni oppilaiden tutkintosuoritukset")
  info("jotta niitä voi hyödyntää haussa")
  info("ja valinnassa")

  feature("Suorituksen tallentaminen") {
    scenario("Tallennetaan yhden henkilön suoritus") {
      Given("Suoritus tallentuu")
      val suoritus = new Suoritus("1.2.5", "KESKEN", "9", "2014", "K", "9D", "1.2.4")

      val system = ActorSystem()
      val suoritusRekisteri = system.actorOf(Props(new SuoritusActor))
      addServlet(new SuoritusServlet(system, suoritusRekisteri), "/rest/v1/suoritukset")

      When("kun se lähetetään (POST)")

      post("/rest/v1/suoritukset", write(suoritus), Map("Content-Type" -> "application/json; charset=utf-8")) {}

      Then("ja se löytyy tallentamisen jälkeen")
      get("/rest/v1/suoritukset")  {
        val parsedBody = parse(body)
        parsedBody.extract[Seq[Suoritus]] should contain(suoritus)
      }
    }
  }
}
