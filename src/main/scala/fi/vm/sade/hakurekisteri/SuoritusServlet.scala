package fi.vm.sade.hakurekisteri

import _root_.akka.actor.{ActorRef, ActorSystem}
import _root_.akka.pattern.ask

import _root_.akka.util.Timeout
import org.scalatra._
import org.json4s.{Formats, DefaultFormats}
import org.scalatra.json._
import scala.concurrent.ExecutionContext

class SuoritusServlet(system: ActorSystem, suoritusActor: ActorRef) extends HakuJaValintarekisteriStack with JacksonJsonSupport with FutureSupport {

  protected implicit def executor: ExecutionContext = system.dispatcher

  protected implicit val jsonFormats: Formats = DefaultFormats

  implicit val defaultTimeout = Timeout(10)



  get("/") {

    contentType = formats("json")

    new AsyncResult() {
      val is = suoritusActor ? SuoritusQuery(params)
    }
  }



  post("/") {
    suoritusActor ! parsedBody.extract[Suoritus]
    Accepted()
  }
  
}


