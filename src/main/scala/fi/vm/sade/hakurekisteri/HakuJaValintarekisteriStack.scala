package fi.vm.sade.hakurekisteri

import fi.vm.sade.hakurekisteri.rest.support.IncidentReporting
import org.scalatra._
import org.slf4j.{Logger, LoggerFactory}

trait HakuJaValintarekisteriStack extends ScalatraServlet with IncidentReporting {

  val logger: Logger = LoggerFactory.getLogger(getClass)

}
