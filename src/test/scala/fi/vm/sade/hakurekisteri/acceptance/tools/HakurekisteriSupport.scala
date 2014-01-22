package fi.vm.sade.hakurekisteri.acceptance.tools

import org.scalatra.test.HttpComponentsClient
import org.json4s.{DefaultFormats, Formats}
import javax.servlet.http.HttpServlet
import fi.vm.sade.hakurekisteri.{SuoritusServlet, SuoritusActor, Suoritus}
import akka.actor.{Props, ActorSystem}
import org.json4s.jackson.JsonMethods._
import fi.vm.sade.hakurekisteri.Suoritus
import org.json4s.jackson.Serialization._
import fi.vm.sade.hakurekisteri.Suoritus


trait HakurekisteriSupport extends HttpComponentsClient {

  protected implicit val jsonFormats: Formats = DefaultFormats

  def addServlet(servlet: HttpServlet, path: String):Unit


  val empty= new Object()

  object db {

    def is(token:Any) = token match {
      case empty => has()
    }

    def has(suoritukset: Suoritus*) = {
      val system = ActorSystem()
      val suoritusRekisteri = system.actorOf(Props(new SuoritusActor(suoritukset)))
      addServlet(new SuoritusServlet(system, suoritusRekisteri), "/rest/v1/suoritukset")
    }

  }


  def allSuoritukset: Seq[Suoritus] = get("/rest/v1/suoritukset") {
    val parsedBody = parse(body)
    parsedBody.extract[Seq[Suoritus]]
  }

  def create (suoritus: Suoritus){
    post("/rest/v1/suoritukset", write(suoritus), Map("Content-Type" -> "application/json; charset=utf-8")) {}
  }
  
  val suoritus = new Suoritus("1.2.3", "KESKEN", "9", "2014", "K", "9D", "1.2.4")

  val suoritus2 =  new Suoritus("1.2.5", "KESKEN", "9", "2014", "K", "9A", "1.2.3")

  val suoritus3 =  new Suoritus("1.2.5", "KESKEN", "9", "2014", "K", "9B", "1.2.6")


}


