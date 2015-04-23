package fi.vm.sade.hakurekisteri.web.proxies

import akka.actor.ActorSystem
import fi.vm.sade.hakurekisteri.Config
import org.scalatra.{FutureSupport, AsyncResult, ScalatraServlet}
import scala.concurrent.{ExecutionContext, Future}

class AuthenticationProxyResource(config: Config, system: ActorSystem) extends ScalatraServlet with FutureSupport {
  implicit val executor: ExecutionContext = system.dispatcher

  val proxy = config.mockMode match {
    case _ => new MockAuthenticationProxy
  }

  get("/buildversion.txt") {
    "artifactId=authentication-service\nmocked"
  }

  before() {
    contentType = "application/json"
  }

  post("/resources/henkilo/henkilotByHenkiloOidList") {
    new AsyncResult() {
      val is = proxy.henkilotByOidList("").map(_.getOrElse("???"))
    }
  }
}

trait AuthenticationProxy {
  def henkilotByOidList(oidList: String): Future[Option[String]]
}

class MockAuthenticationProxy {
  def henkilotByOidList(oidList: String) = Future.successful(Some(getClass.getResourceAsStream("/proxy-mockdata/henkilot-by-oid-list.json")))
}