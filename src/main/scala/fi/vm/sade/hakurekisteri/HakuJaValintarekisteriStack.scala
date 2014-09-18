package fi.vm.sade.hakurekisteri

import fi.vm.sade.hakurekisteri.rest.support.IncidentReporting
import org.scalatra._
import org.slf4j.LoggerFactory

trait HakuJaValintarekisteriStack extends ScalatraServlet with IncidentReporting {

  val logger = LoggerFactory.getLogger(getClass)

}
