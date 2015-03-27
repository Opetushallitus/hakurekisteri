package fi.vm.sade.hakurekisteri.web.haku

import _root_.akka.actor.{ActorSystem, ActorRef}
import _root_.akka.event.{Logging, LoggingAdapter}
import _root_.akka.pattern.AskTimeoutException
import fi.vm.sade.hakurekisteri.integration.hakemus.ReloadHaku
import org.scalatra.swagger.Swagger
import fi.vm.sade.hakurekisteri.rest.support.{SpringSecuritySupport, HakurekisteriJsonSupport}
import org.scalatra.json.JacksonJsonSupport
import org.scalatra._
import scala.concurrent.ExecutionContext
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.web.rest.support.IncidentReport
import fi.vm.sade.hakurekisteri.integration.haku.HakuRequest


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
      import _root_.akka.pattern.ask
      override implicit def timeout: Duration = 60.seconds
      val is = (hakuActor ? HakuRequest)(30.seconds)
    }
  }


  get("/refresh/:oid") {
    currentUser.collect {
      case p if p.isAdmin =>
        hakuActor ! ReloadHaku(params("oid"))
        Some(Accepted())
    }.getOrElse(Unauthorized())

  }

  incident {
    case t: AskTimeoutException => (id) => InternalServerError(IncidentReport(id, "backend service timed out"))
  }
}

