package fi.vm.sade.hakurekisteri

import _root_.akka.actor.{Actor, ActorRef, ActorSystem}
import _root_.akka.pattern.ask

import _root_.akka.util.Timeout
import org.scalatra._
import scalate.ScalateSupport
import org.json4s.{Formats, DefaultFormats}
import org.scalatra.json._
import scala.concurrent.{ExecutionContext, Future}

class SuoritusServlet(system: ActorSystem, suoritusActor: ActorRef) extends HakuJaValintarekisteriStack with JacksonJsonSupport with FutureSupport {

  protected implicit def executor: ExecutionContext = system.dispatcher

  protected implicit val jsonFormats: Formats = DefaultFormats

  implicit val defaultTimeout = Timeout(10)



  get("/") {
    contentType = formats("json")
    new AsyncResult() {
      val is = suoritusActor ? "all"
    }
  }

  post("/") {
    suoritusActor ! parsedBody.extract[Suoritus]
    Accepted()
  }
  
}

class SuoritusActor(var suoritukset:Seq[Suoritus] = Seq()) extends Actor{
  def receive = {
    case "all" => sender ! suoritukset
    case s:Suoritus => {
      suoritukset = (suoritukset.toList :+ s).toSeq
      sender ! suoritukset
    }
  }
}

case class Suoritus(opilaitosOid: String, tila: String, luokkataso: String, arvioituValmistumisvuosi: String, arvioituValmistumiskausi:String, luokka: String, henkiloOid: String)

