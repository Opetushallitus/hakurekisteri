package fi.vm.sade.hakurekisteri.web.proxies

import akka.actor.ActorSystem
import fi.vm.sade.hakurekisteri.Config
import org.scalatra.{InternalServerError, AsyncResult, FutureSupport, ScalatraServlet}
import org.slf4j.LoggerFactory
import scala.concurrent.ExecutionContext

class OPHProxyServlet(system: ActorSystem) extends ScalatraServlet with FutureSupport {
  implicit val executor: ExecutionContext = system.dispatcher
  val log = LoggerFactory.getLogger(getClass)

  before() {
    contentType = "application/json"
  }

  error { case x: Throwable =>
    log.error("OPH proxy fail", x)
    InternalServerError()
  }
}
