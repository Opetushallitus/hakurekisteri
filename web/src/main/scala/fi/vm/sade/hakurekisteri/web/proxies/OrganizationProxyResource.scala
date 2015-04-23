package fi.vm.sade.hakurekisteri.web.proxies

import java.net.URLEncoder
import akka.actor.ActorSystem
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.{ExecutorUtil, VirkailijaRestClient, PreconditionFailedException}
import fi.vm.sade.hakurekisteri.integration.organisaatio.Organisaatio
import org.scalatra.{FutureSupport, AsyncResult, ScalatraServlet}
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

class OrganizationProxyResource(config: Config, system: ActorSystem) extends ScalatraServlet with FutureSupport {
  implicit val executor: ExecutionContext = system.dispatcher
  val proxy = OrganizationProxy.apply(config, system)

  get("/rest/organisaatio/v2/hae") {
    contentType = "application/json"

    new AsyncResult() {
      val is = proxy.search(request.getQueryString).map(_.getOrElse("NOT FOUND")) // TODO not nice
    }
  }
}

object OrganizationProxy {
  def apply(config: Config, system: ActorSystem) = config.mockMode match {
    case true => new MockOrganizationProxy
    case false => new HttpOrganizationProxy(config, system)
  }
}

trait OrganizationProxy {
  def search(query: String): Future[Option[String]]
}

class MockOrganizationProxy extends OrganizationProxy {
  def search(query: String) = Future.successful( Some("""{"numHits":1,"organisaatiot":[{"oid":"1.2.246.562.10.39644336305","alkuPvm":694216800000,"parentOid":"1.2.246.562.10.80381044462","parentOidPath":"1.2.246.562.10.39644336305/1.2.246.562.10.80381044462/1.2.246.562.10.00000000001","oppilaitosKoodi":"06345","oppilaitostyyppi":"oppilaitostyyppi_11#1","match":true,"nimi":{"fi":"Pikkaralan ala-aste"},"kieletUris":["oppilaitoksenopetuskieli_1#1"],"kotipaikkaUri":"kunta_564","children":[],"organisaatiotyypit":["OPPILAITOS"],"aliOrganisaatioMaara":0}]}""") )
}

class HttpOrganizationProxy(config: Config, system: ActorSystem) extends OrganizationProxy {
  implicit val ec: ExecutionContext = system.dispatcher
  // TODO: not nice to duplicate the rest client here
  val organisaatioClient = new VirkailijaRestClient(config.integrations.organisaatioConfig, None)(ec, system) {
    override def serviceName = Some("organisaatio-proxy")
  }

  def search(query: String) = {
    println(query)
    organisaatioClient.readObject[String]("/rest/organisaatio/v2/hae?" + query, 200, 1).map(Option(_))
  }
}