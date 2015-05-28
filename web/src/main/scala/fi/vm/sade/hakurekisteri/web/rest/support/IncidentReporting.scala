package fi.vm.sade.hakurekisteri.web.rest.support

import java.util.UUID

import akka.pattern.AskTimeoutException
import fi.vm.sade.hakurekisteri.integration.hakemus.HakemuksetNotYetLoadedException
import org.joda.time.DateTime
import org.joda.time.DateTime._
import org.scalatra.{ServiceUnavailable, BadRequest, InternalServerError, ActionResult}
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack


case class IncidentReport(incidentId: UUID, message: String, timestamp: DateTime = now(), validationErrors: Seq[String] = Seq())

trait IncidentReporting { this: HakuJaValintarekisteriStack =>

  def incident(handler: PartialFunction[Throwable, (UUID) => ActionResult]): Unit = {
    error {
      case t: AskTimeoutException =>
        val resultGenerator = handler.applyOrElse[Throwable, (UUID) => ActionResult](t, (anything) => (id) => InternalServerError(IncidentReport(id, "back-end service timed out")))
        processError(t) (resultGenerator)
      case t: IllegalArgumentException =>
        val resultGenerator = handler.applyOrElse[Throwable, (UUID) => ActionResult](t, (anything) => (id) => BadRequest(IncidentReport(id, t.getMessage)))
        processError(t) (resultGenerator)
      case t: HakemuksetNotYetLoadedException =>
        val resultGenerator = handler.applyOrElse[Throwable, (UUID) => ActionResult](t, (anything) => (id) => ServiceUnavailable(IncidentReport(id, "hakemukset not yet loaded"), Map("Retry-After" -> "30")))
        processError(t) (resultGenerator)
      case t: Throwable =>
        val resultGenerator = handler.applyOrElse[Throwable, (UUID) => ActionResult](t, (anything) => (id) => InternalServerError(IncidentReport(id, "error in service")))
        processError(t) (resultGenerator)
    }
  }

  def processError(t: Throwable)(handler:(UUID) => ActionResult): ActionResult = {
    val incidentId = UUID.randomUUID()
    logger.error(t, s"incident ${incidentId.toString}")
    handler(incidentId)
  }

}


