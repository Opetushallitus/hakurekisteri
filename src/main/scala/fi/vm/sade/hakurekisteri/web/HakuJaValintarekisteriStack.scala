package fi.vm.sade.hakurekisteri.web

import akka.event.LoggingAdapter
import fi.vm.sade.auditlog.{Audit}
import fi.vm.sade.hakurekisteri.SuoritusAuditVirkailija
import fi.vm.sade.hakurekisteri.integration.OphUrlProperties
import fi.vm.sade.hakurekisteri.web.rest.support.IncidentReporting
import org.scalatra._

trait HakuJaValintarekisteriStack extends ScalatraServlet with IncidentReporting with CorsSupport {

  val logger: LoggingAdapter
  val audit: Audit = SuoritusAuditVirkailija.audit

  if("DEVELOPMENT" == OphUrlProperties.getProperty("common.corsfilter.mode")) {
    options("/*") {
      response.setHeader("Access-Control-Allow-Headers", request.getHeader("Access-Control-Request-Headers"))
    }
  }
}
