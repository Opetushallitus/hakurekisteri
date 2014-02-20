package fi.vm.sade.hakurekisteri

import org.scalatra._
import org.slf4j.LoggerFactory

trait HakuJaValintarekisteriStack extends ScalatraServlet with FlashMapSupport {

  val logger = LoggerFactory.getLogger(getClass)

}
