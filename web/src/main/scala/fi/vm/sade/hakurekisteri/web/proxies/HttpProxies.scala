package fi.vm.sade.hakurekisteri.web.proxies

import fi.vm.sade.hakurekisteri.integration.{OphUrlProperties, VirkailijaRestClient}
import fi.vm.sade.properties.UrlUtils
import org.json4s._
import scala.concurrent.Future

class HttpProxies(authenticationClient: VirkailijaRestClient, koodistoClient: VirkailijaRestClient, organizationClient: VirkailijaRestClient) extends Proxies {
  lazy val koodisto = new KoodistoProxy {
    def koodi(path: String): Future[JValue] = {
      val url = UrlUtils.joinUrl(OphUrlProperties.ophProperties.url("koodisto-service.restBase"), path)
      koodistoClient.readObjectFromUrl[JValue](url, 200, 1)
    }
  }
  lazy val authentication = new AuthenticationProxy {
    override def henkilotByOidList(oidList: List[String]) = {
      authenticationClient.postObject[List[String], String]("authentication-service.henkilotByHenkiloOidList")(200, oidList)
    }
    def henkiloByOid(oid: String) = {
      authenticationClient.readObject[String]("authentication-service.henkilo", oid)(200, 1)
    }

    def henkiloByQparam(hetu: String): Future[String] = {
      authenticationClient.readObject[String]("authentication-service.henkiloSearchQ",hetu, "1")(200 , 1)
    }
  }
  lazy val organization = new OrganizationProxy {
    def search(query: AnyRef) = {
      organizationClient.readObject[String]("organisaatio-service.haeV2",query)(200, 1)
    }

    def get(oid: String) = {
      organizationClient.readObject[String]("organisaatio-service.organisaatio", oid)(200, 1)
    }
  }
}