package fi.vm.sade.hakurekisteri.rest

import _root_.akka.actor.{ActorRef, ActorSystem}
import _root_.akka.pattern.ask

import _root_.akka.util.Timeout
import org.scalatra._
import org.json4s.{Formats, DefaultFormats}
import org.scalatra.json._
import scala.concurrent.ExecutionContext
import fi.vm.sade.hakurekisteri.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.domain.Suoritus
import fi.vm.sade.hakurekisteri.query.SuoritusQuery

class SuoritusServlet(system: ActorSystem, suoritusActor: ActorRef) extends HakuJaValintarekisteriStack with JacksonJsonSupport with FutureSupport {

  protected implicit def executor: ExecutionContext = system.dispatcher

  protected implicit val jsonFormats: Formats = DefaultFormats

  implicit val defaultTimeout = Timeout(10)

  before() {
    contentType = formats("json")
  }

  get("/") {
    new AsyncResult() {
      val is = suoritusActor ? SuoritusQuery(params)
    }
  }

  post("/") {
    new AsyncResult() {
      val is = suoritusActor ? parsedBody.extract[Suoritus]
    }
  }
  
}


