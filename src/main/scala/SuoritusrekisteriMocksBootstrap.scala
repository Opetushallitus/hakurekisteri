import javax.servlet.{Servlet, ServletContext}

import _root_.akka.actor.ActorSystem
import fi.vm.sade.hakurekisteri.integration.mocks.{HenkiloMock, KoodistoMock, OrganisaatioMock}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.hakurekisteri.web.jonotus.{Siirtotiedostojono, SiirtotiedostojonoResource}
import fi.vm.sade.hakurekisteri.web.proxies._
import org.atmosphere.cache.SessionBroadcasterCache
import org.atmosphere.cpr.{DefaultBroadcaster, AtmosphereInterceptor, Broadcaster}
import org.atmosphere.interceptor.AtmosphereResourceLifecycleInterceptor
import org.atmosphere.util.ExcludeSessionBroadcaster
import org.json4s.{Extraction, _}
import org.json4s.jackson.JsonMethods._
import org.scalatra._
import org.scalatra.atmosphere.{JacksonSimpleWireformat, DefaultScalatraBroadcaster, AtmosphereClient}

import scala.concurrent.{ExecutionContext, Future}

class SuoritusrekisteriMocksBootstrap extends LifeCycle with HakurekisteriJsonSupport {

  override def init(context: ServletContext) {
    val config = WebAppConfig.getConfig(context)
    implicit val system = config.productionServerConfig.system
    implicit val ec = config.productionServerConfig.ec
    implicit val security = config.productionServerConfig.security
    val r = new SiirtotiedostojonoResource(new Siirtotiedostojono())
    context.mount(r, "/mocks/suoritusrekisteri/siirtotiedostojono")
    context.mount(new OrganizationProxyServlet(system), "/organisaatio-service")
    context.mount(new AuthenticationProxyServlet(system), "/authentication-service")
    context.mount(new KoodistoProxyServlet(system), "/koodisto-service")
    context.mount(new LocalizationMockServlet(system), "/lokalisointi")
    context.mount(new CasMockServlet(system), "/cas")
  }

  class OrganizationProxyServlet(system: ActorSystem) extends OPHProxyServlet(system) {
    get("/rest/organisaatio/v2/hae") {
      new AsyncResult() {
        override val is = Future.successful(OrganisaatioMock.findAll())
      }
    }

    get("/rest/organisaatio/:oid") {
      new AsyncResult() {
        override val is = Future.successful(OrganisaatioMock.findByOid(params("oid")))
      }
    }
  }

  class AuthenticationProxyServlet(system: ActorSystem) extends OPHProxyServlet(system) with HakurekisteriJsonSupport {
    get("/buildversion.txt") {
      contentType = "text/plain"
      "artifactId=authentication-service\nmocked"
    }

    get("/resources/henkilo/:oid") {
      new AsyncResult() {
        override val is = henkiloByOid(params("oid"))
      }
    }

    get("/resources/henkilo") {
      new AsyncResult() {
        override val is = henkiloByQparam(params("q"))
      }
    }

    post("/resources/henkilo/henkilotByHenkiloOidList") {
      new AsyncResult() {
        val parsedBody = parse(request.body)
        override val is = henkilotByOidList(parsedBody.extract[List[String]])
      }
    }

    def henkilotByOidList(oidList: List[String]) = Future.successful(HenkiloMock.henkilotByHenkiloOidList(oidList))
    def henkiloByOid(oid: String) = Future.successful(HenkiloMock.getHenkiloByOid(oid))
    def henkiloByQparam(hetu: String) = Future.successful(HenkiloMock.getHenkiloByQParam(hetu))
  }

  class KoodistoProxyServlet(system: ActorSystem)(implicit ec: ExecutionContext) extends OPHProxyServlet(system) with HakurekisteriJsonSupport {
    get("""/rest/json/(.*)""".r) {
      new AsyncResult() {
        val path = multiParams("captures").head
        override val is = koodi(path).map(compact(_))
      }
    }
    val koodit: Map[String, JValue] = Extraction.extract[Map[String, JValue]](parse(KoodistoMock.getKoodisto()))

    def koodi(path: String) = {
      Future.successful(koodit(path.replaceAll("/$", "")))
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
}
