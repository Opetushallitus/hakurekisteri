package fi.vm.sade.hakurekisteri.web.rest.support

import java.util.UUID

import _root_.akka.pattern.AskTimeoutException
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import org.joda.time.DateTime
import org.joda.time.DateTime._
import org.scalatra._

case class IncidentReport(
  incidentId: UUID,
  message: String,
  timestamp: DateTime = now(),
  validationErrors: Seq[String] = Seq()
)

trait IncidentReporting { this: HakuJaValintarekisteriStack =>

  def incident(handler: PartialFunction[Throwable, (UUID) => ActionResult]): Unit = {
    error {
      case t: NotFoundException =>
        val resultGenerator = handler.applyOrElse[Throwable, (UUID) => ActionResult](
          t,
          (anything) => (id) => NotFound(IncidentReport(id, s"resource not found: ${t.resource}"))
        )
        processError(t, false)(resultGenerator)
      case t: AskTimeoutException =>
        val resultGenerator = handler.applyOrElse[Throwable, (UUID) => ActionResult](
          t,
          (anything) =>
            (id) => InternalServerError(IncidentReport(id, "back-end service timed out"))
        )
        processError(t)(resultGenerator)
      case t: IllegalArgumentException =>
        val resultGenerator = handler.applyOrElse[Throwable, (UUID) => ActionResult](
          t,
          (anything) => (id) => BadRequest(IncidentReport(id, t.getMessage))
        )
        processError(t)(resultGenerator)
      case t: UserNotAuthorized =>
        val resultGenerator = handler.applyOrElse[Throwable, (UUID) => ActionResult](
          t,
          (anything) => (id) => Forbidden(IncidentReport(id, s"not authorized"))
        )
        processError(t, false)(resultGenerator)
      case t: Throwable =>
        val resultGenerator = handler.applyOrElse[Throwable, (UUID) => ActionResult](
          t,
          (anything) => (id) => InternalServerError(IncidentReport(id, "error in service"))
        )
        processError(t)(resultGenerator)
    }
  }

  def processError(t: Throwable, printStack: Boolean = true)(
    handler: (UUID) => ActionResult
  ): ActionResult = {
    val incidentId = UUID.randomUUID()
    if (printStack) {
      logger.error(t, s"incident ${incidentId.toString}")
    } else {
      logger.error(s"incident ${incidentId.toString}: ${t.toString}")
    }
    handler(incidentId)
  }

}
