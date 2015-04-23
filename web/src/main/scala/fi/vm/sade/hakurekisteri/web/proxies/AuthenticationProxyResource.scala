package fi.vm.sade.hakurekisteri.web.proxies

import _root_.akka.actor.ActorSystem
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import org.scalatra._
import org.scalatra.json.JsonSupport
import scala.concurrent.Future
import scala.io.Source
import org.json4s._
import org.json4s.jackson.JsonMethods._

class AuthenticationProxyResource(config: Config, system: ActorSystem) extends OPHProxyServlet(system) with HakurekisteriJsonSupport {
  val proxy = config.mockMode match {
    case true => new MockAuthenticationProxy
    case false => new HttpAuthenticationProxy(config, system)
  }

  get("/buildversion.txt") {
    "artifactId=authentication-service\nmocked"
  }

  post("/resources/henkilo/henkilotByHenkiloOidList") {

    new AsyncResult() {
      val parsedBody = parse(request.body)
      val is = proxy.henkilotByOidList(parsedBody.extract[List[String]])
    }
  }
}

trait AuthenticationProxy {
  def henkilotByOidList(oidList: List[String]): Future[String]
}

class MockAuthenticationProxy extends AuthenticationProxy {
  lazy val data = Source.fromInputStream(getClass.getResourceAsStream("/proxy-mockdata/henkilot-by-oid-list.json")).mkString
  def henkilotByOidList(oidList: List[String]) = Future.successful(data)
}

class HttpAuthenticationProxy(config: Config, system: ActorSystem) extends OphProxy(config, system, config.integrations.henkiloConfig, "authentication-proxy") with AuthenticationProxy {
  override def henkilotByOidList(oidList: List[String]) = {
    client.postObject[List[String], String]("/resources/henkilo/henkilotByHenkiloOidList", 200, oidList)
  }
}