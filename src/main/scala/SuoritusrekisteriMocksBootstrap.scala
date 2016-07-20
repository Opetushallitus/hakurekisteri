import javax.servlet.{Servlet, ServletContext}

import _root_.akka.actor.ActorSystem
import fi.vm.sade.hakurekisteri.integration.mocks.{HenkiloMock, KoodistoMock, OrganisaatioMock}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.hakurekisteri.web.proxies._
import org.json4s.{Extraction, _}
import org.json4s.jackson.JsonMethods._
import org.scalatra._
import support.SpecResource

import scala.concurrent.{ExecutionContext, Future}

class SuoritusrekisteriMocksBootstrap extends LifeCycle with HakurekisteriJsonSupport {
  lazy val koodistoProxy = new KoodistoProxy {
    lazy val koodit: Map[String, JValue] = Extraction.extract[Map[String, JValue]](parse(KoodistoMock.getKoodisto()))

    def koodi(path: String) = {
      Future.successful(koodit(path.replaceAll("/$", "")))
    }
  }
  lazy val authenticationProxy = new AuthenticationProxy {
    def henkilotByOidList(oidList: List[String]) = Future.successful(HenkiloMock.henkilotByHenkiloOidList(oidList))

    def henkiloByOid(oid: String) = Future.successful(HenkiloMock.getHenkiloByOid(oid))

    def henkiloByQparam(hetu: String) = Future.successful(HenkiloMock.getHenkiloByQParam(hetu))
  }
  lazy val organizationProxy = new OrganizationProxy {
    def search(query: AnyRef): Future[String] = Future.successful(OrganisaatioMock.findAll())

    def get(oid: String): Future[String] = Future.successful(OrganisaatioMock.findByOid(oid))
  }

  override def init(context: ServletContext) {
    val config = WebAppConfig.getConfig(context)
    implicit val system = config.productionServerConfig.system
    implicit val ec = config.productionServerConfig.ec
    implicit val security = config.productionServerConfig.security
    context.mount(new OrganizationProxyServlet(organizationProxy, system), "/organisaatio-service")
    context.mount(new AuthenticationProxyServlet(authenticationProxy, system), "/authentication-service")
    context.mount(new KoodistoProxyServlet(koodistoProxy, system), "/koodisto-service")
    context.mount(new LocalizationMockServlet(system), "/lokalisointi")
    context.mount(new CasMockServlet(system), "/cas")
    context.mount(new SpecResource(config.productionServerConfig.integrations.ytl), "/spec")
  }
  def mountServlets(context: ServletContext)(servlets: ((String, String), Servlet with Handler)*) {
    implicit val sc = context
    for (((path, name), servlet) <- servlets) context.mount(handler = servlet, urlPattern = path, name = name, loadOnStartup = 1)
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

class KoodistoProxyServlet(proxy: KoodistoProxy, system: ActorSystem)(implicit ec: ExecutionContext) extends OPHProxyServlet(system) with HakurekisteriJsonSupport {
  get("""/rest/json/(.*)""".r) {
    new AsyncResult() {
      val path = multiParams("captures").head
      val is = proxy.koodi(path).map(compact(_))
    }
  }
}

class LocalizationMockServlet(system: ActorSystem) extends OPHProxyServlet(system) {
  get("/cxf/rest/v1/localisation") {
    getClass.getResourceAsStream("/proxy-mockdata/localization.json")
  }

  post("/cxf/rest/v1/localisation") {
    Created("{}")
  }
}

class CasMockServlet(system: ActorSystem) extends OPHProxyServlet(system) {
  get("/myroles") {
    getClass.getResourceAsStream("/proxy-mockdata/cas-myroles.json")
  }
}
