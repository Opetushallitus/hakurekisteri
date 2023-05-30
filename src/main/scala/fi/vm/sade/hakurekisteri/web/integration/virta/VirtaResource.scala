package fi.vm.sade.hakurekisteri.web.integration.virta

import _root_.akka.actor.{ActorRef, ActorSystem}
import _root_.akka.event.{Logging, LoggingAdapter}
import _root_.akka.pattern.AskTimeoutException
import fi.vm.sade.hakurekisteri.integration.virta.{RescheduleVirtaProcessing, VirtaStatus, _}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.web.rest.support._
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.{Swagger, SwaggerEngine}

import scala.concurrent.{ExecutionContext, Future}

object Status extends Enumeration {
  type Status = Value
  val OK, TIMEOUT, FAILURE = Value
}

class VirtaResource(virtaQueue: ActorRef)(implicit
  system: ActorSystem,
  val security: Security,
  sw: Swagger
) extends HakuJaValintarekisteriStack
    with HakurekisteriJsonSupport
    with JacksonJsonSupport
    with FutureSupport
    with SecuritySupport
    with VirtaSwaggerApi {

  override protected implicit def executor: ExecutionContext = system.dispatcher
  override protected implicit def swagger: SwaggerEngine[_] = sw
  override protected def applicationDescription: String = "VIRTA-rajapinnat"

  override val logger: LoggingAdapter = Logging.getLogger(system, this)

  import _root_.akka.pattern.ask

  import scala.concurrent.duration._

  before() {
    contentType = formats("json")
  }

  def virtaStatus: Future[VirtaStatus] =
    (virtaQueue ? VirtaHealth)(120.seconds).mapTo[VirtaStatus].recover {
      case e: AskTimeoutException => VirtaStatus(queueLength = 0, status = Status.TIMEOUT)
      case e: Throwable           => VirtaStatus(queueLength = 0, status = Status.FAILURE)
    }

  def hasAccess: Boolean = currentUser.exists(_.isAdmin)

  get("/process", operation(process)) {
    if (!hasAccess) throw UserNotAuthorized("not authorized")
    else
      new AsyncResult() {
        override implicit def timeout: Duration = 120.seconds

        virtaQueue ! StartVirtaProcessing

        override val is = virtaStatus
      }
  }

  get("/cancel") {
    if (!hasAccess) throw UserNotAuthorized("not authorized")
    else
      new AsyncResult() {
        override implicit def timeout: Duration = 120.seconds

        virtaQueue ! CancelSchedule

        override val is = virtaStatus
      }
  }

  //Raikasta yhden oppijan tiedot Virrasta
  get("/refresh/:oppijaOid", operation(refreshOppija)) {
    if (!hasAccess) throw UserNotAuthorized("not authorized")
    else {
      val oppijaOid = params("oppijaOid")
      val logXml = params("logXml").toBoolean
      new AsyncResult() {
        override implicit def timeout: Duration = 120.seconds
        virtaQueue ! RefreshOppijaFromVirta(oppijaOid, logXml)
        override val is = virtaStatus
      }
    }
  }

  get("/refresh/haku/:hakuOid", operation(refreshHaku)) {
    if (!hasAccess) throw UserNotAuthorized("not authorized")
    else {
      val hakuOid = params("hakuOid")
      new AsyncResult() {
        override implicit def timeout: Duration = 120.seconds

        virtaQueue ! RefreshHakuFromVirta(hakuOid)
        override val is = virtaStatus
      }
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

  get("/status", operation(statusQuery)) {
    if (!hasAccess) throw UserNotAuthorized("not authorized")
    else
      new AsyncResult() {
        override implicit def timeout: Duration = 120.seconds

        override val is = virtaStatus
      }
  }

  incident { case t: UserNotAuthorized =>
    (id) => Forbidden(IncidentReport(id, t.message))
  }
}
