package fi.vm.sade.hakurekisteri.rest.support

import java.util.UUID

import akka.pattern.AskTimeoutException
import fi.vm.sade.hakurekisteri.HakuJaValintarekisteriStack
import org.joda.time.DateTime
import org.joda.time.DateTime._
import org.scalatra.{InternalServerError, ActionResult}


trait IncidentReporting { this: HakuJaValintarekisteriStack =>

  case class IncidentReport(incidentId: UUID, message: String, timestamp: DateTime = now())

  def incident(handler: PartialFunction[Throwable, (UUID) => ActionResult]): Unit = {
    error {
      case t: AskTimeoutException =>
        val resultGenerator = handler.applyOrElse[Throwable, (UUID) => ActionResult](t, (anything) => (id) => InternalServerError(IncidentReport(id, "back-end service timed out")))
        processError(t) (resultGenerator)
      case t: Throwable =>
        val resultGenerator = handler.applyOrElse[Throwable, (UUID) => ActionResult](t, (anything) => (id) => InternalServerError(IncidentReport(id, "error in service")))
        processError(t) (resultGenerator)
    }
  }

  def processError(t: Throwable)(handler:(UUID) => ActionResult): ActionResult = {
    val incidentId = UUID.randomUUID()
    logger.error(t, s"incindent ${incidentId.toString}")
    handler(incidentId)
  }

}


