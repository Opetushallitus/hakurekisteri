package fi.vm.sade.hakurekisteri.integration.hakemus

import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class HakemusService(restClient: VirkailijaRestClient) {

  def hakemuksetForPerson(personOid: String): Future[Seq[FullHakemus]] = {
    for (
      hakemukset <- restClient.postObject[Set[String], Map[String, Seq[FullHakemus]]]("haku-app.bypersonoid")(200, Set(personOid))
    ) yield hakemukset.getOrElse(personOid, Seq[FullHakemus]())
  }

  def hakemuksetForHakukohde(hakukohdeOid: String, organisaatio: Option[String]): Future[Seq[FullHakemus]] = {
    restClient.postObject[Set[String], Seq[FullHakemus]]("haku-app.byapplicationoption", organisaatio)(200, Set(hakukohdeOid))
  }

  def personOidsForHaku(hakuOid: String, organisaatio: Option[String]): Future[Set[String]] = {
    restClient.postObject[Set[String], Set[String]]("haku-app.personoidsbyapplicationsystem", organisaatio)(200, Set(hakuOid))
  }

  def personOidsForHakukohde(hakukohdeOid: String, organisaatio: Option[String]): Future[Set[String]] = {
    restClient.postObject[Set[String], Set[String]]("haku-app.personoidsbyapplicationsystem", organisaatio)(200, Set(hakukohdeOid))
  }

  def hakemuksetForHaku(hakuOid: String, organisaatio: Option[String]): Future[Seq[FullHakemus]] = {
    restClient.postObject[Set[String], Seq[FullHakemus]]("haku-app.personoidsbyapplicationsystem", organisaatio)(200, Set(hakuOid))
  }

}

class HakemusServiceMock extends HakemusService(null) {
  override def hakemuksetForPerson(personOid: String) = Future.successful(Seq[FullHakemus]())

  override def hakemuksetForHakukohde(hakukohdeOid: String, organisaatio: Option[String]) = Future.successful(Seq[FullHakemus]())

  override def personOidsForHaku(hakuOid: String, organisaatio: Option[String]) = Future.successful(Set[String]())

  override def personOidsForHakukohde(hakukohdeOid: String, organisaatio: Option[String]) = Future.successful(Set[String]())

  override def hakemuksetForHaku(hakuOid: String, organisaatio: Option[String]) = Future.successful(Seq[FullHakemus]())
}