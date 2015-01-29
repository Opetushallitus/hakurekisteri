package fi.vm.sade.hakurekisteri.web

import akka.event.LoggingAdapter
import org.scalatra._
import fi.vm.sade.hakurekisteri.web.rest.support.IncidentReporting

trait HakuJaValintarekisteriStack extends ScalatraServlet with IncidentReporting {

  val logger: LoggingAdapter

}
