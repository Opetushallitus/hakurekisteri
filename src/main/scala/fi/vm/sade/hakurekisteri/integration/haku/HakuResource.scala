package fi.vm.sade.hakurekisteri.integration.haku

import akka.actor.{ActorSystem, ActorRef}
import akka.event.{Logging, LoggingAdapter}
import akka.pattern.AskTimeoutException
import org.scalatra.swagger.Swagger
import fi.vm.sade.hakurekisteri.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.rest.support.{IncidentReport, SpringSecuritySupport, HakurekisteriJsonSupport}
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.{InternalServerError, AsyncResult, CorsSupport, FutureSupport}
import scala.concurrent.ExecutionContext
import akka.util.Timeout


class HakuResource(hakuActor: ActorRef)(implicit system: ActorSystem, sw: Swagger) extends HakuJaValintarekisteriStack with HakurekisteriJsonSupport with JacksonJsonSupport with FutureSupport with CorsSupport with SpringSecuritySupport  {
  override protected implicit def executor: ExecutionContext = system.dispatcher
  override val logger: LoggingAdapter = Logging.getLogger(system, this)

  options("/*") {
    response.setHeader("Access-Control-Allow-Headers", request.getHeader("Access-Control-Request-Headers"))
  }

  before() {
    contentType = formats("json")
  }

  get("/") {
    new AsyncResult() {
      import scala.concurrent.duration._
      import akka.pattern.ask
      override implicit def timeout: Duration = 60.seconds
      val is = (hakuActor ? HakuRequest)(30.seconds)
    }
  }

  incident {
    case t: AskTimeoutException => (id) => InternalServerError(IncidentReport(id, "backend service timed out"))
  }
}

