package fi.vm.sade.hakurekisteri.integration.hakemus

import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient

import scala.concurrent.{Future, Await}
import scala.concurrent.duration._

trait HakemusService {

  def hakemuksetForPerson(personOid: String): Seq[FullHakemus]

  def hakemuksetForHakukohde(hakukohdeOid: String): Seq[FullHakemus]

  def personOidsForHaku(hakuOid: String, organisaatio: Option[String]): Future[Set[String]]

  def personOidsForHakukohde(hakukohdeOid: String, organisaatio: Option[String]): Future[Set[String]]

  def hakemuksetForHaku(hakuOid: String): Future[Seq[FullHakemus]]

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

  override def personOidsForHaku(hakuOid: String, organisaatio: Option[String]) = {
    restClient.postObject[Set[String], Set[String]]("haku-app.personoidsbyapplicationsystem", organisaatio)(200, Set(hakuOid))
  }

  override def personOidsForHakukohde(hakukohdeOid: String, organisaatio: Option[String]) = {
    restClient.postObject[Set[String], Set[String]]("haku-app.personoidsbyapplicationsystem", organisaatio)(200, Set(hakukohdeOid))
  }

  override def hakemuksetForHaku(hakuOid: String): Future[Seq[FullHakemus]] = {
    restClient.postObject[Set[String], Seq[FullHakemus]]("haku-app.personoidsbyapplicationsystem")(200, Set(hakuOid))
  }
}

class MockHakemusService extends HakemusService {
  override def hakemuksetForPerson(personOid: String): Seq[FullHakemus] = Seq[FullHakemus]()

  override def hakemuksetForHakukohde(hakukohdeOid: String): Seq[FullHakemus] = Seq[FullHakemus]()

  override def personOidsForHaku(hakuOid: String, organisaatio: Option[String]) = Future.successful(Set[String]())

  override def personOidsForHakukohde(hakukohdeOid: String, organisaatio: Option[String]) = Future.successful(Set[String]())

  override def hakemuksetForHaku(hakuOid: String): Future[Seq[FullHakemus]] = Future.successful(Seq[FullHakemus]())
}