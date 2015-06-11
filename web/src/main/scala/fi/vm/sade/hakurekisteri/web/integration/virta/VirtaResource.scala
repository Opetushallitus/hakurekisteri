package fi.vm.sade.hakurekisteri.web.integration.virta

import _root_.akka.actor.{ActorSystem, ActorRef}
import _root_.akka.event.{Logging, LoggingAdapter}
import _root_.akka.pattern.AskTimeoutException
import fi.vm.sade.hakurekisteri.{Oids, Config}
import fi.vm.sade.hakurekisteri.healthcheck.Status
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport

import scala.concurrent.{Future, ExecutionContext}
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.web.rest.support._
import fi.vm.sade.hakurekisteri.integration.virta._
import fi.vm.sade.hakurekisteri.integration.virta.RescheduleVirtaProcessing
import fi.vm.sade.hakurekisteri.integration.virta.VirtaStatus

class VirtaResource(virtaQueue: ActorRef) (implicit system: ActorSystem, val security: Security) extends HakuJaValintarekisteriStack with HakurekisteriJsonSupport with JacksonJsonSupport with FutureSupport with CorsSupport with SecuritySupport {

  override protected implicit def executor: ExecutionContext = system.dispatcher

  override val logger: LoggingAdapter = Logging.getLogger(system, this)

  import scala.concurrent.duration._
  import _root_.akka.pattern.ask

  before() {
    contentType = formats("json")
  }

  def virtaStatus: Future[VirtaStatus] = (virtaQueue ? VirtaHealth)(120.seconds).mapTo[VirtaStatus].recover {
    case e: AskTimeoutException => VirtaStatus(queueLength = 0, status = Status.TIMEOUT)
    case e: Throwable => VirtaStatus(queueLength = 0, status = Status.FAILURE)
  }

  def hasAccess: Boolean = currentUser.exists(_.isAdmin)

  get("/process") {
    if (!hasAccess) throw UserNotAuthorized("not authorized")
    else new AsyncResult() {
      override implicit def timeout: Duration = 120.seconds

      virtaQueue ! StartVirtaProcessing

      override val is = virtaStatus
    }
  }

  get("/cancel") {
    if (!hasAccess) throw UserNotAuthorized("not authorized")
    else new AsyncResult() {
      override implicit def timeout: Duration = 120.seconds

      virtaQueue ! CancelSchedule

      override val is = virtaStatus
    }
  }

  get("/reschedule") {
    if (!hasAccess) throw UserNotAuthorized("not authorized")
    else {
      new AsyncResult() {
        override implicit def timeout: Duration = 120.seconds

        virtaQueue ! RescheduleVirtaProcessing

        override val is = virtaStatus
      }
    }
  }

  get("/status") {
    if (!hasAccess) throw UserNotAuthorized("not authorized")
    else new AsyncResult() {
      override implicit def timeout: Duration = 120.seconds

      override val is = virtaStatus
    }
  }

  incident {
    case t: UserNotAuthorized => (id) => Forbidden(IncidentReport(id, t.message))
  }
}
