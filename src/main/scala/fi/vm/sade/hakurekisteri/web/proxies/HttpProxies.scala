package fi.vm.sade.hakurekisteri.web.proxies

import fi.vm.sade.hakurekisteri.integration.{VirkailijaRestClient}

class HttpProxies(valintarekisteriClient: VirkailijaRestClient) extends Proxies {
  lazy val vastaanottotiedot = new VastaanottotiedotProxy {
    override def historia(henkiloOid: String) = {
      valintarekisteriClient
        .readObject[String]("valinta-tulos-service.vastaanottotiedot", henkiloOid)(200, 1)
    }
  }
}
