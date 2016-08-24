package fi.vm.sade.hakurekisteri.integration.hakemus

import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

import akka.actor.Scheduler
import fi.vm.sade.hakurekisteri.hakija.HakijaQuery
import fi.vm.sade.hakurekisteri.integration.{ServiceConfig, VirkailijaRestClient}
import fi.vm.sade.hakurekisteri.rest.support.Query

import scala.compat.Platform
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

case class HakemusConfig(serviceConf: ServiceConfig, maxApplications: Int)

case class HakemusQuery(haku: Option[String], organisaatio: Option[String] = None, hakukohdekoodi: Option[String] = None, hakukohde: Option[String] = None) extends Query[FullHakemus]

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

  case class SearchParams(aoOids: String = null, asId: String = null, organizationFilter: String = null,
                          updatedAfter: String = null, start: Int = 0, rows: Int = pageSize)

  var triggers: Seq[Trigger] = Seq()

  def hakemuksetForPerson(personOid: String): Future[Seq[FullHakemus]] = {
    for (
      hakemukset <- restClient.postObject[Set[String], Map[String, Seq[FullHakemus]]]("haku-app.bypersonoid")(200, Set(personOid))
    ) yield hakemukset.getOrElse(personOid, Seq[FullHakemus]())
  }

  def hakemuksetForPersonsInHaku(personOids: Set[String], hakuOid: String): Future[Seq[FullHakemus]] = {
    for (
      hakemuksetByPerson <- restClient.postObject[Set[String], Map[String, Seq[FullHakemus]]]("haku-app.bypersonoid")(200, personOids)
    ) yield hakemuksetByPerson.values.flatten.filter(_.applicationSystemId == hakuOid).toSeq
  }

  def hakemuksetForHakukohde(hakukohdeOid: String, organisaatio: Option[String]): Future[Seq[FullHakemus]] = {
    fetchHakemukset(params = SearchParams(aoOids = hakukohdeOid, organizationFilter = organisaatio.orNull))
  }

  def hakemuksetForHaku(hakuOid: String, organisaatio: Option[String]): Future[Seq[FullHakemus]] = {
    fetchHakemukset(params = SearchParams(asId = hakuOid, organizationFilter = organisaatio.orNull))
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

  def processModifiedHakemukset(modifiedAfter: Date = new Date(Platform.currentTime - TimeUnit.DAYS.toMillis(2)),
                                refreshFrequency: FiniteDuration = 1.minute)(implicit scheduler: Scheduler): Unit = {
    scheduler.scheduleOnce(refreshFrequency)({
      val lastChecked = new Date()
      fetchHakemukset(params = SearchParams(updatedAfter = new SimpleDateFormat("yyyyMMddHHmm").format(modifiedAfter))).onSuccess {
        case hakemukset: Seq[FullHakemus] =>
          triggerHakemukset(hakemukset)
          processModifiedHakemukset(lastChecked, refreshFrequency)
      }
    })
  }

  private def fetchHakemukset(page: Int = 0, params: SearchParams): Future[Seq[FullHakemus]] = {
    restClient.readObject[List[FullHakemus]]("haku-app.listfull", params.copy(start = page * pageSize))(acceptedResponseCode = 200, maxRetries = 2)
      .flatMap(hakemukset =>
        if (hakemukset.length < pageSize) {
          Future.successful(hakemukset)
        } else {
          fetchHakemukset(page + 1, params).map(hakemukset ++ _)
        })
  }
}

class HakemusServiceMock extends HakemusService(null) {
  override def hakemuksetForPerson(personOid: String) = Future.successful(Seq[FullHakemus]())

  override def hakemuksetForHakukohde(hakukohdeOid: String, organisaatio: Option[String]) = Future.successful(Seq[FullHakemus]())

  override def personOidsForHaku(hakuOid: String, organisaatio: Option[String]) = Future.successful(Set[String]())

  override def personOidsForHakukohde(hakukohdeOid: String, organisaatio: Option[String]) = Future.successful(Set[String]())

  override def hakemuksetForHaku(hakuOid: String, organisaatio: Option[String]) = Future.successful(Seq[FullHakemus]())

  override def hakemuksetForPersonsInHaku(personOids: Set[String], hakuOid: String) = Future.successful(Seq[FullHakemus]())
}
