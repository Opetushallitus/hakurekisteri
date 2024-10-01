package fi.vm.sade.hakurekisteri.web.proxies

import fi.vm.sade.hakurekisteri.integration.mocks._
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport

import scala.concurrent.Future

class MockProxies extends Proxies with HakurekisteriJsonSupport {
  lazy val vastaanottotiedot = new VastaanottotiedotProxy {
    override def historia(henkiloOid: String): Future[String] =
      Future.successful(ValintarekisteriMock.getHistoria(henkiloOid))
  }
}
