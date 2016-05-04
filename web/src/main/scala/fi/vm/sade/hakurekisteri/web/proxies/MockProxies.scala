package fi.vm.sade.hakurekisteri.web.proxies

import fi.vm.sade.hakurekisteri.integration.mocks._
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import org.json4s._
import org.json4s.jackson.JsonMethods._

import scala.concurrent.Future

class MockProxies extends Proxies with HakurekisteriJsonSupport {
  lazy val koodisto = new KoodistoProxy {
    lazy val koodit: Map[String, JValue] = Extraction.extract[Map[String, JValue]](parse(KoodistoMock.getKoodisto()))

    def koodi(path: String) = {
      Future.successful(koodit(path.replaceAll("/$", "")))
    }
  }
  lazy val authentication = new AuthenticationProxy {
    def henkilotByOidList(oidList: List[String]) = Future.successful(HenkiloMock.henkilotByHenkiloOidList(oidList))

    def henkiloByOid(oid: String) = Future.successful(HenkiloMock.getHenkiloByOid(oid))

    def henkiloByQparam(hetu: String) = Future.successful(HenkiloMock.getHenkiloByQParam(hetu))
  }
  lazy val organization = new OrganizationProxy {
    def search(query: AnyRef): Future[String] = Future.successful(OrganisaatioMock.findAll())

    def get(oid: String): Future[String] = Future.successful(OrganisaatioMock.findByOid(oid))
  }
  lazy val vastaanottotiedot = new VastaanottotiedotProxy {
    override def historia(henkiloOid: String): Future[String] = Future.successful(ValintarekisteriMock.getHistoria(henkiloOid))
  }
}