package fi.vm.sade.hakurekisteri.acceptance

import org.scalatra.test.scalatest.ScalatraFeatureSpec
import org.scalatest.GivenWhenThen
import fi.vm.sade.hakurekisteri.{SuoritusActor, Suoritus, SuoritusServlet}
import org.json4s._
import org.json4s.jackson.JsonMethods._
import akka.actor.{Props, ActorSystem}
import java.beans.PropertyChangeSupport

class HaeValmistuvatSpec extends ScalatraFeatureSpec with GivenWhenThen  {
  protected implicit val jsonFormats: Formats = DefaultFormats

  info("Peruskoulua päättävänä hakijana")
  info("haluan Oponi löytävän tietoni hakupalvelusta")
  info("jotta saan ohjausta")
  info("ja apua hakuprosessissa")

  feature("Lähtökoulu- ja luokka-tiedot") {
    scenario("Haetaan suoritustietoja henkiloOidilla ja arvioidulla valmistumiskaudella") {
      Given("Henkilön suoritus on tietokannassa")
      val suoritus = new Suoritus("1.2.3", "KESKEN", "9", "2014", "K", "9D", "1.2.4")
      val system = ActorSystem()
      val suoritusRekisteri = system.actorOf(Props(new SuoritusActor(Seq(suoritus))))

      addServlet(new SuoritusServlet(system, suoritusRekisteri), "/rest/v1/suoritukset")

      When("hakemalla")
      val haettu = get("/rest/v1/suoritukset")  {
        val parsedBody = parse(body)
        parsedBody.extract[Seq[Suoritus]]
      }

      Then("tiedot löytyvät")
      haettu should equal(Seq(suoritus))
    }
  }
}

