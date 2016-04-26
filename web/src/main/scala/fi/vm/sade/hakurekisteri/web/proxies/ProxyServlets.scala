package fi.vm.sade.hakurekisteri.web.proxies

import javax.servlet.ServletContext

import _root_.akka.actor.ActorSystem
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.scalatra._
import org.scalatra.servlet.ServletApiImplicits._
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext

object ProxyServlets {
  def mount(proxies: Proxies, context: ServletContext)(implicit system: ActorSystem) {
    context.mount(new OrganizationProxyServlet(proxies.organization, system), "/organisaatio-service")
    context.mount(new AuthenticationProxyServlet(proxies.authentication, system), "/authentication-service")
    context.mount(new KoodistoProxyServlet(proxies.koodisto, system), "/koodisto-service")
    context.mount(new VastaanottotiedotProxyServlet(proxies.vastaanottotiedot, system), "/vastaanottotiedot")
    context.mount(new LocalizationMockServlet(system), "/lokalisointi")
    context.mount(new CasMockServlet(system), "/cas")
  }
}

class OrganizationProxyServlet(proxy: OrganizationProxy, system: ActorSystem) extends OPHProxyServlet(system) {
  get("/rest/organisaatio/v2/hae") {
    new AsyncResult() {
      val is = proxy.search(request.getParameterMap)
    }
  }

  get("/rest/organisaatio/:oid") {
    new AsyncResult() {
      val is = proxy.get(params("oid"))
    }
  }
}

class AuthenticationProxyServlet(proxy: AuthenticationProxy, system: ActorSystem) extends OPHProxyServlet(system) with HakurekisteriJsonSupport {
  get("/buildversion.txt") {
    contentType = "text/plain"
    "artifactId=authentication-service\nmocked"
  }

  get("/resources/henkilo/:oid") {
    new AsyncResult() {
      val is = proxy.henkiloByOid(params("oid"))
    }
  }

  get("/resources/henkilo") {
    new AsyncResult() {
      val is = proxy.henkiloByQparam(params("q"))
    }
  }

  post("/resources/henkilo/henkilotByHenkiloOidList") {
    new AsyncResult() {
      val parsedBody = parse(request.body)
      val is = proxy.henkilotByOidList(parsedBody.extract[List[String]])
    }
  }
}

class KoodistoProxyServlet(proxy: KoodistoProxy, system: ActorSystem) extends OPHProxyServlet(system) with HakurekisteriJsonSupport {
  get("""/rest/json/(.*)""".r) {
    new AsyncResult() {
      val path = multiParams("captures").head
      val is = proxy.koodi(path).map(compact(_))
    }
  }
}

class VastaanottotiedotProxyServlet(proxy: VastaanottotiedotProxy, system: ActorSystem) extends OPHProxyServlet(system) with HakurekisteriJsonSupport {
  get("/:personOid") {
    new AsyncResult() {
      val is = proxy.historia(params("personOid"))
    }
  }
}

class LocalizationMockServlet(system: ActorSystem) extends OPHProxyServlet(system) {
  get("/cxf/rest/v1/localisation") {
    getClass.getResourceAsStream("/proxy-mockdata/localization.json")
  }

  post("/cxf/rest/v1/localisation") {
    Created
  }
}

class CasMockServlet(system: ActorSystem) extends OPHProxyServlet(system) {
  get("/myroles") {
    getClass.getResourceAsStream("/proxy-mockdata/cas-myroles.json")
  }
}

protected class OPHProxyServlet(system: ActorSystem) extends ScalatraServlet with FutureSupport {
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