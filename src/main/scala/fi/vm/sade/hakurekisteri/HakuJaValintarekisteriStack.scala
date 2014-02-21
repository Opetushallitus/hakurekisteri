package fi.vm.sade.hakurekisteri

import org.scalatra._
import org.slf4j.LoggerFactory

trait HakuJaValintarekisteriStack extends ScalatraServlet with CorsSupport {

  val logger = LoggerFactory.getLogger(getClass)

  options("/*"){
    response.setHeader("Access-Control-Allow-Headers", request.getHeader("Access-Control-Request-Headers"))

  }

}
