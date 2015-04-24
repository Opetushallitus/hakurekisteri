package fi.vm.sade.hakurekisteri.web.proxies

import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient
import org.json4s._
import scala.concurrent.Future

class HttpProxies(authenticationClient: VirkailijaRestClient, koodistoClient: VirkailijaRestClient, organizationClient: VirkailijaRestClient) extends Proxies {
  lazy val koodisto = new KoodistoProxy {
    def koodi(path: String): Future[JValue] = {
      koodistoClient.readObject[JValue]("/rest/json/" + path, 200, 1)
    }
  }
  lazy val authentication = new AuthenticationProxy {
    override def henkilotByOidList(oidList: List[String]) = {
      authenticationClient.postObject[List[String], String]("/resources/henkilo/henkilotByHenkiloOidList", 200, oidList)
    }
    def henkiloByOid(oid: String) = {
      authenticationClient.readObject[String]("/resources/henkilo/" + oid, 200, 1)
    }
  }
  lazy val organization = new OrganizationProxy {
    def search(query: String) = {
      organizationClient.readObject[String]("/rest/organisaatio/v2/hae?" + query, 200, 1)
    }

    def get(oid: String) = {
      organizationClient.readObject[String]("/rest/organisaatio/" + oid, 200, 1)
    }
  }
}