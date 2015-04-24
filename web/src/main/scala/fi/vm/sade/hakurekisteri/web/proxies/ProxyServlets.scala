package fi.vm.sade.hakurekisteri.web.proxies

import javax.servlet.ServletContext
import akka.actor.ActorSystem
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import org.scalatra.{InternalServerError, FutureSupport, ScalatraServlet, AsyncResult}
import org.scalatra.servlet.ServletApiImplicits
import ServletApiImplicits._
import org.slf4j.LoggerFactory
import scala.concurrent.ExecutionContext

object ProxyServlets {
  def mount(proxies: Proxies, context: ServletContext)(implicit system: ActorSystem) {
    context.mount(new OrganizationProxyServlet(proxies.organization, system), "/organisaatio-service")
    context.mount(new AuthenticationProxyServlet(proxies.authentication, system), "/authentication-service")
    context.mount(new KoodistoProxyServlet(proxies.koodisto, system), "/koodisto-service")
    context.mount(new LocalizationProxyServlet, "/lokalisointi")
  }
}

class OrganizationProxyServlet(proxy: OrganizationProxy, system: ActorSystem) extends OPHProxyServlet(system) {
  get("/rest/organisaatio/v2/hae") {
    new AsyncResult() {
      val is = proxy.search(request.getQueryString)
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
    "artifactId=authentication-service\nmocked"
  }

  get("/resources/henkilo/:oid") {
    new AsyncResult() {
      val is = proxy.henkiloByOid(params("oid"))
    }
  }

  post("/resources/henkilo/henkilotByHenkiloOidList") {
    import org.json4s._
    import org.json4s.jackson.JsonMethods._
    new AsyncResult() {
      val parsedBody = parse(request.body)
      val is = proxy.henkilotByOidList(parsedBody.extract[List[String]])
    }
  }
}

class KoodistoProxyServlet(proxy: KoodistoProxy, system: ActorSystem) extends OPHProxyServlet(system) with HakurekisteriJsonSupport {
  get("/rest/json/:id/koodi*") {
    proxy.koodi(params("id"))
  }
}

class LocalizationProxyServlet extends ScalatraServlet {
  get("/cxf/rest/v1/localisation") {
    getClass.getResourceAsStream("/proxy-mockdata/localization.json")
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