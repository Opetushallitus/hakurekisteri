package fi.vm.sade.hakurekisteri.web.proxies

import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import org.json4s._
import org.json4s.jackson.JsonMethods._

import scala.concurrent.Future
import scala.io.Source

class MockProxies extends Proxies with HakurekisteriJsonSupport {
  lazy val koodisto = new KoodistoProxy {
    lazy val koodit: Map[String, JValue] = Extraction.extract[Map[String, JValue]](parse(getClass.getResourceAsStream("/proxy-mockdata/koodisto.json")))
    def koodi(id: String) = koodit(id)
  }
  lazy val authentication = new AuthenticationProxy {
    lazy val data = Source.fromInputStream(getClass.getResourceAsStream("/proxy-mockdata/henkilot-by-oid-list.json")).mkString
    def henkilotByOidList(oidList: List[String]) = Future.successful(data)
    def henkiloByOid(oid: String) = Future.successful("""{"id":109341,"etunimet":"aa","syntymaaika":"1958-10-12","passinnumero":null,"hetu":"123456-789","kutsumanimi":"aa","oidHenkilo":"1.2.246.562.24.71944845619","oppijanumero":null,"sukunimi":"AA","sukupuoli":"1","turvakielto":null,"henkiloTyyppi":"OPPIJA","eiSuomalaistaHetua":false,"passivoitu":false,"yksiloity":false,"yksiloityVTJ":true,"yksilointiYritetty":false,"duplicate":false,"created":null,"modified":null,"kasittelijaOid":null,"asiointiKieli":null,"aidinkieli":null,"huoltaja":null,"kayttajatiedot":null,"kielisyys":[],"kansalaisuus":[],"yhteystiedotRyhma":[]}""")
  }
  lazy val organization = new OrganizationProxy {
    def search(query: String) = Future.successful( """{"numHits":1,"organisaatiot":[{"oid":"1.2.246.562.10.39644336305","alkuPvm":694216800000,"parentOid":"1.2.246.562.10.80381044462","parentOidPath":"1.2.246.562.10.39644336305/1.2.246.562.10.80381044462/1.2.246.562.10.00000000001","oppilaitosKoodi":"06345","oppilaitostyyppi":"oppilaitostyyppi_11#1","match":true,"nimi":{"fi":"Pikkaralan ala-aste"},"kieletUris":["oppilaitoksenopetuskieli_1#1"],"kotipaikkaUri":"kunta_564","children":[],"organisaatiotyypit":["OPPILAITOS"],"aliOrganisaatioMaara":0}]}""" )
    def get(oid: String) = Future.successful("""{"oid":"1.2.246.562.10.39644336305","nimi":{"fi":"Pikkaralan ala-aste"},"alkuPvm":"1992-01-01","postiosoite":{"osoiteTyyppi":"posti","yhteystietoOid":"1.2.246.562.5.75344290822","postinumeroUri":"posti_90310","osoite":"Vasantie 121","postitoimipaikka":"OULU","ytjPaivitysPvm":null,"lng":null,"lap":null,"coordinateType":null,"osavaltio":null,"extraRivi":null,"maaUri":null},"parentOid":"1.2.246.562.10.80381044462","parentOidPath":"|1.2.246.562.10.00000000001|1.2.246.562.10.80381044462|","vuosiluokat":[],"oppilaitosKoodi":"06345","kieletUris":["oppilaitoksenopetuskieli_1#1"],"oppilaitosTyyppiUri":"oppilaitostyyppi_11#1","yhteystiedot":[{"kieli":"kieli_fi#1","id":"22913","yhteystietoOid":"1.2.246.562.5.11296174961","email":"kaisa.tahtinen@ouka.fi"},{"tyyppi":"faksi","kieli":"kieli_fi#1","id":"22914","yhteystietoOid":"1.2.246.562.5.18105745956","numero":"08  5586 1582"},{"tyyppi":"puhelin","kieli":"kieli_fi#1","id":"22915","yhteystietoOid":"1.2.246.562.5.364178776310","numero":"08  5586 9514"},{"kieli":"kieli_fi#1","id":"22916","yhteystietoOid":"1.2.246.562.5.94533742915","www":"http://www.edu.ouka.fi/koulut/pikkarala"},{"osoiteTyyppi":"posti","kieli":"kieli_fi#1","id":"22917","yhteystietoOid":"1.2.246.562.5.75344290822","osoite":"Vasantie 121","postinumeroUri":"posti_90310","postitoimipaikka":"OULU","ytjPaivitysPvm":null,"coordinateType":null,"lap":null,"lng":null,"osavaltio":null,"extraRivi":null,"maaUri":null},{"osoiteTyyppi":"kaynti","kieli":"kieli_fi#1","id":"22918","yhteystietoOid":"1.2.246.562.5.58988409759","osoite":"Vasantie 121","postinumeroUri":"posti_90310","postitoimipaikka":"OULU","ytjPaivitysPvm":null,"coordinateType":null,"lap":null,"lng":null,"osavaltio":null,"extraRivi":null,"maaUri":null}],"kuvaus2":{},"tyypit":["Oppilaitos"],"yhteystietoArvos":[],"nimet":[{"nimi":{"fi":"Pikkaralan ala-aste"},"alkuPvm":"1992-01-01","version":1}],"kayntiosoite":{"osoiteTyyppi":"kaynti","yhteystietoOid":"1.2.246.562.5.58988409759","postinumeroUri":"posti_90310","osoite":"Vasantie 121","postitoimipaikka":"OULU","ytjPaivitysPvm":null,"lng":null,"lap":null,"coordinateType":null,"osavaltio":null,"extraRivi":null,"maaUri":null},"kotipaikkaUri":"kunta_564","maaUri":"maatjavaltiot1_fin","ryhmatyypit":[],"kayttoryhmat":[],"version":1,"status":"AKTIIVINEN"}""")
  }
}