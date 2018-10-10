package fi.vm.sade.hakurekisteri.web

import akka.event.LoggingAdapter
import fi.vm.sade.auditlog.{ApplicationType, Audit}
import fi.vm.sade.hakurekisteri.{AuditUtil, LoggerForAudit}
import fi.vm.sade.hakurekisteri.integration.OphUrlProperties
import fi.vm.sade.hakurekisteri.web.rest.support.IncidentReporting
import org.scalatra._
import org.slf4j.LoggerFactory

trait HakuJaValintarekisteriStack extends ScalatraServlet with IncidentReporting with CorsSupport {

  val logger: LoggingAdapter
  val auditUtil = new AuditUtil
  val audit = new Audit(new LoggerForAudit ,"hakurekisteri", ApplicationType.VIRKAILIJA)

  if("DEVELOPMENT" == OphUrlProperties.getProperty("common.corsfilter.mode")) {
    options("/*") {
      response.setHeader("Access-Control-Allow-Headers", request.getHeader("Access-Control-Request-Headers"))
    }
  }
}
