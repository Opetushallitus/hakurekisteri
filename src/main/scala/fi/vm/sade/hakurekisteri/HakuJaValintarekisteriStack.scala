package fi.vm.sade.hakurekisteri

import _root_.akka.event.LoggingAdapter
import fi.vm.sade.hakurekisteri.rest.support.IncidentReporting
import org.scalatra._

trait HakuJaValintarekisteriStack extends ScalatraServlet with IncidentReporting {

  val logger: LoggingAdapter

}
