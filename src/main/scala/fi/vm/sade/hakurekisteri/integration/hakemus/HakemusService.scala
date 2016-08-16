package fi.vm.sade.hakurekisteri.integration.hakemus

import java.text.SimpleDateFormat
import java.util.Date

import akka.actor.Scheduler
import fi.vm.sade.hakurekisteri.hakija.HakijaQuery
import fi.vm.sade.hakurekisteri.integration.{ServiceConfig, VirkailijaRestClient}
import fi.vm.sade.hakurekisteri.rest.support.Query

import scala.compat.Platform
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}

case class HakemusConfig(serviceConf: ServiceConfig, maxApplications: Int)

case class HakemusQuery(haku: Option[String], organisaatio: Option[String], hakukohdekoodi: Option[String], hakukohde: Option[String] = None) extends Query[FullHakemus]

case class HenkiloHakijaQuery(henkilo: String) extends Query[FullHakemus]

object HakemusQuery {

  def apply(hq: HakijaQuery): HakemusQuery = HakemusQuery(hq.haku, hq.organisaatio, hq.hakukohdekoodi)
}

case class Trigger(f: (FullHakemus) => Unit)

object Trigger {
  def apply(f: (String, String, String) => Unit): Trigger = Trigger(_ match {
    case FullHakemus(_, Some(personOid), hakuOid, Some(answers), _, _) =>
      for (
        henkilo <- answers.henkilotiedot;
        hetu <- henkilo.Henkilotunnus
      ) f(personOid, hetu, hakuOid)

    case _ =>
  })
}

class HakemusService(restClient: VirkailijaRestClient, pageSize: Int = 2000) {

  var triggers: Seq[Trigger] = Seq()

  def hakemuksetForPerson(personOid: String): Future[Seq[FullHakemus]] = {
    for (
      hakemukset <- restClient.postObject[Set[String], Map[String, Seq[FullHakemus]]]("haku-app.bypersonoid")(200, Set(personOid))
    ) yield hakemukset.getOrElse(personOid, Seq[FullHakemus]())
  }

  def hakemuksetForHakukohde(hakukohdeOid: String, organisaatio: Option[String]): Future[Seq[FullHakemus]] = {
    restClient.postObject[Set[String], Seq[FullHakemus]]("haku-app.byapplicationoption", organisaatio.orNull)(200, Set(hakukohdeOid))
  }

  def hakemuksetForHaku(hakuOid: String, organisaatio: Option[String]): Future[Seq[FullHakemus]] = {
    restClient.postObject[Set[String], Seq[FullHakemus]]("haku-app.byapplicationsystem", organisaatio.orNull)(200, Set(hakuOid))
  }

  def personOidsForHaku(hakuOid: String, organisaatio: Option[String]): Future[Set[String]] = {
    restClient.postObject[Set[String], Set[String]]("haku-app.personoidsbyapplicationsystem", organisaatio.orNull)(200, Set(hakuOid))
  }

  def personOidsForHakukohde(hakukohdeOid: String, organisaatio: Option[String]): Future[Set[String]] = {
    restClient.postObject[Set[String], Set[String]]("haku-app.personoidsbyapplicationoption", organisaatio.orNull)(200, Set(hakukohdeOid))
  }

  def addTrigger(trigger: Trigger) = triggers = triggers :+ trigger

  private def triggerHakemukset(hakemukset: Seq[FullHakemus]) =
    hakemukset.foreach(hakemus =>
      triggers.foreach(trigger => trigger.f(hakemus))
    )

  private val twoDaysAgo = 1000 * 60 * 60 * 24 * 2

  def processModifiedHakemukset(modifiedAfter: Date = new Date(Platform.currentTime - twoDaysAgo),
                                refreshFrequency: FiniteDuration = 1.minute)(implicit scheduler: Scheduler): Unit = {
    scheduler.scheduleOnce(refreshFrequency)({
      fetchHakemukset(modifiedAfter = new SimpleDateFormat("yyyyMMddHHmm").format(modifiedAfter)).future.onSuccess {
        case hakemukset: Seq[FullHakemus] =>
          triggerHakemukset(hakemukset)
          processModifiedHakemukset(modifiedAfter = new Date(Platform.currentTime - (5 * 60 * 1000)), refreshFrequency)
      }
    })
  }

  private def getQueryParams(page: Int, modifiedAfter: String) = {
    Map(
      "updatedAfter" -> modifiedAfter,
      "start" -> page * pageSize,
      "rows" -> pageSize
    )
  }

  private def fetchHakemukset(page: Int = 0, modifiedAfter: String): Promise[Seq[FullHakemus]] = {
    val futureHakemukset: Future[List[FullHakemus]] = restClient.readObject[List[FullHakemus]]("haku-app.listfull", getQueryParams(page, modifiedAfter))(acceptedResponseCode = 200, maxRetries = 2)

    val promise = Promise[Seq[FullHakemus]]()

    for (hakemukset <- futureHakemukset) {
      if (hakemukset.length < pageSize) {
        promise.success(hakemukset)
      } else {
        fetchHakemukset(page + 1, modifiedAfter).future.onSuccess {
          case nextPageHakemukset: Seq[FullHakemus] => promise.success(hakemukset ++ nextPageHakemukset)
        }
      }
    }

    promise
  }

}

class HakemusServiceMock extends HakemusService(null) {
  override def hakemuksetForPerson(personOid: String) = Future.successful(Seq[FullHakemus]())

  override def hakemuksetForHakukohde(hakukohdeOid: String, organisaatio: Option[String]) = Future.successful(Seq[FullHakemus]())

  override def personOidsForHaku(hakuOid: String, organisaatio: Option[String]) = Future.successful(Set[String]())

  override def personOidsForHakukohde(hakukohdeOid: String, organisaatio: Option[String]) = Future.successful(Set[String]())

  override def hakemuksetForHaku(hakuOid: String, organisaatio: Option[String]) = Future.successful(Seq[FullHakemus]())
}