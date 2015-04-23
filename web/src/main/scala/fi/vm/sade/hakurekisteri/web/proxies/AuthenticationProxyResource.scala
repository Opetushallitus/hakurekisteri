package fi.vm.sade.hakurekisteri.web.proxies

import _root_.akka.actor.ActorSystem
import fi.vm.sade.hakurekisteri.Config
import org.scalatra._

import scala.concurrent.Future
import scala.io.Source

class AuthenticationProxyResource(config: Config, system: ActorSystem) extends OPHProxyServlet(system) {
  val proxy = config.mockMode match {
    case true => new MockAuthenticationProxy
    case false => new HttpAuthenticationProxy(config, system)
  }

  get("/buildversion.txt") {
    "artifactId=authentication-service\nmocked"
  }

  post("/resources/henkilo/henkilotByHenkiloOidList") {
    new AsyncResult() {
      val is = proxy.henkilotByOidList(request.body)
    }
  }
}

trait AuthenticationProxy {
  def henkilotByOidList(oidList: String): Future[String]
}

class MockAuthenticationProxy extends AuthenticationProxy {
  lazy val data = Source.fromInputStream(getClass.getResourceAsStream("/proxy-mockdata/henkilot-by-oid-list.json")).mkString
  def henkilotByOidList(oidList: String) = Future.successful(data)
}

class HttpAuthenticationProxy(config: Config, system: ActorSystem) extends OphProxy(config, system, config.integrations.henkiloConfig, "authentication-proxy") with AuthenticationProxy {
  override def henkilotByOidList(oidList: String) = {
    client.postObject[String, String]("/resources/henkilo/henkilotByHenkiloOidList", 200, oidList)
  }
}