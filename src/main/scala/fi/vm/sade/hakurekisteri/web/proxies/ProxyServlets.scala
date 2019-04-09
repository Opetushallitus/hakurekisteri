package fi.vm.sade.hakurekisteri.web.proxies


import _root_.akka.actor.ActorSystem
import fi.vm.sade.hakurekisteri.integration.ExecutorUtil
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import org.scalatra._
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext

class VastaanottotiedotProxyServlet(proxy: VastaanottotiedotProxy, system: ActorSystem) extends OPHProxyServlet(system) with HakurekisteriJsonSupport {
  get("/:personOid") {
    new AsyncResult() {
      val is = proxy.historia(params("personOid"))
    }
  }
}

class OPHProxyServlet(system: ActorSystem) extends ScalatraServlet with FutureSupport {
  implicit val executor: ExecutionContext = ExecutorUtil.createExecutor(8, getClass.getSimpleName)
  val log = LoggerFactory.getLogger(getClass)

  before() {
    contentType = "application/json"
  }

  error { case x: Throwable =>
    log.error("OPH proxy fail", x)
    InternalServerError()
  }
}
