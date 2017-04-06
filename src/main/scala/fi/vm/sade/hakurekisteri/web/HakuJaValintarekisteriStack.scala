package fi.vm.sade.hakurekisteri.web

import akka.event.LoggingAdapter
import fi.vm.sade.auditlog.{Audit, ApplicationType}
import fi.vm.sade.hakurekisteri.integration.OphUrlProperties
import fi.vm.sade.hakurekisteri.web.rest.support.IncidentReporting
import org.scalatra._

trait HakuJaValintarekisteriStack extends ScalatraServlet with IncidentReporting with CorsSupport {

  val logger: LoggingAdapter
  val audit = AuditLogger.audit

  if("DEVELOPMENT" == OphUrlProperties.getProperty("common.corsfilter.mode")) {
    options("/*") {
      response.setHeader("Access-Control-Allow-Headers", request.getHeader("Access-Control-Request-Headers"))
    }
  }
}

object AuditLogger {
  val audit = new Audit("hakurekisteri", ApplicationType.VIRKAILIJA)
}