package fi.vm.sade.hakurekisteri.web.haku

import _root_.akka.actor.{ActorRef, ActorSystem}
import _root_.akka.event.{Logging, LoggingAdapter}
import _root_.akka.pattern.AskTimeoutException
import fi.vm.sade.hakurekisteri.integration.haku.HakuRequest
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.web.rest.support.{IncidentReport, Security, SecuritySupport}
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.Swagger

import scala.concurrent.ExecutionContext


class HakuResource(hakuActor: ActorRef)(implicit system: ActorSystem, sw: Swagger, val security: Security) extends HakuJaValintarekisteriStack with HakurekisteriJsonSupport with JacksonJsonSupport with FutureSupport with SecuritySupport  {
  override protected implicit def executor: ExecutionContext = system.dispatcher
  override val logger: LoggingAdapter = Logging.getLogger(system, this)

  before() {
    contentType = formats("json")
  }

  get("/") {
    new AsyncResult() {
      import _root_.akka.pattern.ask

      import scala.concurrent.duration._
      override implicit def timeout: Duration = 60.seconds
      val is = (hakuActor ? HakuRequest)(30.seconds)
    }
  }

  incident {
    case t: AskTimeoutException => (id) => InternalServerError(IncidentReport(id, "backend service timed out"))
  }
}

