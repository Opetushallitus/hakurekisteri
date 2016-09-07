package fi.vm.sade.hakurekisteri.web.proxies

import scala.concurrent.Future

trait Proxies {
  def vastaanottotiedot: VastaanottotiedotProxy
}

trait VastaanottotiedotProxy {
  def historia(henkiloOid: String): Future[String]
}
