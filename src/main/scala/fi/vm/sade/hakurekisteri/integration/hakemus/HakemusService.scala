package fi.vm.sade.hakurekisteri.integration.hakemus

import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient

import scala.concurrent.Await
import scala.concurrent.duration._

trait HakemusService {

  def hakemuksetForPerson(personOid: String): Seq[FullHakemus]

  def hakemuksetForHakukohde(hakukohdeOid: String): Seq[FullHakemus]

  def personOidsForHaku(hakuOid: String): Set[String]

}

class RemoteHakemusService(restClient: VirkailijaRestClient) extends HakemusService {

  val timeout = 180.seconds

  override def hakemuksetForPerson(personOid: String): Seq[FullHakemus] = {
    val future = restClient.postObject[Set[String], Map[String, Seq[FullHakemus]]]("haku-app.bypersonoid")(200, Set(personOid))
    val hakemuksetByPersonOid: Map[String, Seq[FullHakemus]] = Await.result(future, timeout)
    hakemuksetByPersonOid.getOrElse(personOid, Seq[FullHakemus]())
  }

  override def hakemuksetForHakukohde(hakukohdeOid: String): Seq[FullHakemus] = {
    val future = restClient.postObject[Set[String], Seq[FullHakemus]]("haku-app.byapplicationoption")(200, Set(hakukohdeOid))
    Await.result(future, timeout)
  }

  override def personOidsForHaku(hakuOid: String) = {
    val future = restClient.postObject[Set[String], Set[String]]("haku-app.byapplicationsystem")(200, Set(hakuOid))
    Await.result(future, timeout)
  }
}

class MockHakemusService extends HakemusService {
  override def hakemuksetForPerson(personOid: String): Seq[FullHakemus] = Seq[FullHakemus]()

  override def hakemuksetForHakukohde(hakukohdeOid: String): Seq[FullHakemus] = Seq[FullHakemus]()

  override def personOidsForHaku(hakuOid: String): Set[String] = Set[String]()
}