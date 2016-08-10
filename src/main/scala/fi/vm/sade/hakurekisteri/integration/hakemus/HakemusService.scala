package fi.vm.sade.hakurekisteri.integration.hakemus

import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

trait HakemusService {

  def hakemuksetByPerson(personOid: String): Seq[FullHakemus]

  def hakemuksetByHakukohde(hakukohdeOid: String): Seq[FullHakemus]

  def hakemuksetByHaku(hakuOid: String): Seq[FullHakemus]

}

class RemoteHakemusService(restClient: VirkailijaRestClient) extends HakemusService {

  override def hakemuksetByPerson(personOid: String): Seq[FullHakemus] = {
    val future = restClient.postObject[Set[String], Map[String, Seq[FullHakemus]]]("haku-app.bypersonoid")(200, Set(personOid))
    val hakemuksetByPersonOid: Map[String, Seq[FullHakemus]] = Await.result(future, 10.seconds)
    hakemuksetByPersonOid.getOrElse(personOid, Seq[FullHakemus]())
  }

  override def hakemuksetByHakukohde(hakukohdeOid: String) = {
    Seq[FullHakemus]()
  }

  override def hakemuksetByHaku(hakuOid: String) = {
    Seq[FullHakemus]()
  }
}

class MockHakemusService extends HakemusService {
  override def hakemuksetByPerson(personOid: String): Seq[FullHakemus] = Seq[FullHakemus]()

  override def hakemuksetByHakukohde(hakukohdeOid: String): Seq[FullHakemus] = Seq[FullHakemus]()

  override def hakemuksetByHaku(hakuOid: String): Seq[FullHakemus] = Seq[FullHakemus]()
}