package fi.vm.sade.hakurekisteri.web.integration.virta

import akka.actor.{ActorRef, ActorSystem}
import akka.event.{Logging, LoggingAdapter}
import akka.pattern.{AskTimeoutException, ask}
import fi.vm.sade.hakurekisteri.integration.virta.VirtaQuery
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.web.rest.support.{IncidentReport, Security, SecuritySupport, UserNotAuthorized}
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.{Swagger, SwaggerEngine}
import org.scalatra.{FutureSupport, AsyncResult, InternalServerError}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.matching.Regex

class VirtaSuoritusResource(val virtaActor: ActorRef)
                           (implicit val system: ActorSystem, sw: Swagger, val security: Security)
  extends HakuJaValintarekisteriStack  with HakurekisteriJsonSupport with VirtaSuoritusSwaggerApi with JacksonJsonSupport with SecuritySupport with FutureSupport {
  override val logger: LoggingAdapter = Logging.getLogger(system, this)
  override protected implicit def swagger: SwaggerEngine[_] = sw
  override protected implicit def executor: ExecutionContext = system.dispatcher
  override protected def applicationDescription: String = "Henkilön suoritusten haun rajapinta Virta-palvelusta"
  val hetuPattern: Regex = "[0-9]{6}[A+-][0-9]{3}[0-9A-FHJ-NPR-Y]$".r

  // XXX: Replace with proper permission checks
  def hasAccess: Boolean = true // currentUser.exists(_.isAdmin)

  before() {
    contentType = formats("json")
  }

  def parsePIN(id: String): Option[String] = {
    hetuPattern.findFirstIn(id)
  }

  get("/:id", operation(query)) {
    if (!hasAccess) throw UserNotAuthorized("not authorized")
    else new AsyncResult() {
      override implicit def timeout: Duration = 120.seconds
      override val is = (virtaActor ? VirtaQuery(oppijanumero=params("id"), hetu=parsePIN(params("id"))))(120.seconds)
    }
  }

  incident {
    case t: AskTimeoutException => (id) => InternalServerError(IncidentReport(id, "back-end service timed out"))
    case e: Throwable => (id) => InternalServerError(IncidentReport(id, "unexpected error"))
  }


}