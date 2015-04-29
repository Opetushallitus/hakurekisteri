package fi.vm.sade.hakurekisteri.web.proxies

import org.json4s._
import scala.concurrent.Future

trait Proxies {
  def koodisto: KoodistoProxy
  def authentication: AuthenticationProxy
  def organization: OrganizationProxy
}

trait KoodistoProxy {
  def koodi(id: String): Future[JValue]
}

trait AuthenticationProxy {
  def henkilotByOidList(oidList: List[String]): Future[String]
  def henkiloByOid(oid: String): Future[String]
  def henkiloByQparam(hetu: String): Future[String]
}

trait OrganizationProxy {
  def search(query: String): Future[String]
  def get(oid: String): Future[String]
}