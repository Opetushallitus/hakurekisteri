package fi.vm.sade.hakurekisteri.web

import akka.event.LoggingAdapter
import fi.vm.sade.auditlog.Audit
import fi.vm.sade.hakurekisteri.web.rest.support.IncidentReporting
import org.scalatra._

trait HakuJaValintarekisteriStack extends ScalatraServlet with IncidentReporting {

  val logger: LoggingAdapter
  val audit = new Audit()

}
