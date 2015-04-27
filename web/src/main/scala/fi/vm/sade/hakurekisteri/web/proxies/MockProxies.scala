package fi.vm.sade.hakurekisteri.web.proxies

import fi.vm.sade.hakurekisteri.integration.mocks.OrganisaatioMock
import fi.vm.sade.hakurekisteri.integration.organisaatio.{OrganisaatioResponse, Organisaatio}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import org.json4s._
import org.json4s.jackson.JsonMethods._

import scala.concurrent.Future
import scala.io.Source

class MockProxies extends Proxies with HakurekisteriJsonSupport {
  lazy val koodisto = new KoodistoProxy {
    lazy val koodit: Map[String, JValue] = Extraction.extract[Map[String, JValue]](parse(getClass.getResourceAsStream("/proxy-mockdata/koodisto.json")))
    def koodi(path: String) = {
      Future.successful(koodit(path.replaceAll("/$", "")))
    }
  }
  lazy val authentication = new AuthenticationProxy {
    lazy val data = Source.fromInputStream(getClass.getResourceAsStream("/proxy-mockdata/henkilot-by-oid-list.json")).mkString
    def henkilotByOidList(oidList: List[String]) = Future.successful(data)
    def henkiloByOid(oid: String) = Future.successful("""{"id":109341,"etunimet":"aa","syntymaaika":"1958-10-12","passinnumero":null,"hetu":"123456-789","kutsumanimi":"aa","oidHenkilo":"1.2.246.562.24.71944845619","oppijanumero":null,"sukunimi":"AA","sukupuoli":"1","turvakielto":null,"henkiloTyyppi":"OPPIJA","eiSuomalaistaHetua":false,"passivoitu":false,"yksiloity":false,"yksiloityVTJ":true,"yksilointiYritetty":false,"duplicate":false,"created":null,"modified":null,"kasittelijaOid":null,"asiointiKieli":null,"aidinkieli":null,"huoltaja":null,"kayttajatiedot":null,"kielisyys":[],"kansalaisuus":[],"yhteystiedotRyhma":[]}""")
  }
  lazy val organization = new OrganizationProxy {
    def search(query: String): Future[String] = Future.successful(OrganisaatioMock.findAll())
    def get(oid: String): Future[String] = Future.successful(OrganisaatioMock.findByOid(oid))
  }
}