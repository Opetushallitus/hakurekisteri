package fi.vm.sade.hakurekisteri.integration.hakemus

import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem, Scheduler}
import akka.event.Logging
import akka.pattern.ask
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.hakija.HakijaQuery
import fi.vm.sade.hakurekisteri.integration.henkilo.{Henkilo, IOppijaNumeroRekisteri, PersonOidsWithAliases}
import fi.vm.sade.hakurekisteri.integration.organisaatio.Organisaatio
import fi.vm.sade.hakurekisteri.integration.tarjonta.{Hakukohde, HakukohdeQuery}
import fi.vm.sade.hakurekisteri.integration.{ServiceConfig, VirkailijaRestClient}
import fi.vm.sade.hakurekisteri.rest.support.Query

import scala.compat.Platform
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

case class HakemusConfig(serviceConf: ServiceConfig, maxApplications: Int)

case class HakemusQuery(haku: Option[String], organisaatio: Option[String] = None, hakukohdekoodi: Option[String] = None, hakukohde: Option[String] = None) extends Query[FullHakemus]

case class HenkiloHakijaQuery(henkilo: String) extends Query[FullHakemus]

object HakemusQuery {
  def apply(hq: HakijaQuery): HakemusQuery = HakemusQuery(hq.haku, hq.organisaatio, hq.hakukohdekoodi)
}

case class Trigger(f: (FullHakemus, PersonOidsWithAliases) => Unit)

object Trigger {
  def apply(f: (String, String, String, PersonOidsWithAliases) => Unit): Trigger = {
    def processHakemusWithPersonOid(fullHakemus: FullHakemus, personOidsWithAliases: PersonOidsWithAliases): Unit = (fullHakemus, personOidsWithAliases) match {
      case (FullHakemus(_, Some(personOid), hakuOid, Some(answers), _, _, _), personOidsWithAliases) =>
        for (
          henkilo <- answers.henkilotiedot;
          hetu <- henkilo.Henkilotunnus)
          f(personOid, hetu, hakuOid, personOidsWithAliases)
      case _ =>
    }

    new Trigger(processHakemusWithPersonOid)
  }
}

case class HetuPersonOid(hetu: String, personOid: String)

case class ListFullSearchDto(searchTerms: String = "",
                             states: List[String] = List(),
                             aoOids: List[String] = List(),
                             asIds: List[String] = List(),
                             keys: List[String])

object ListFullSearchDto {
  val commonKeys = List("oid", "applicationSystemId", "personOid")

  def suoritusvuosi(hakukohdeOid: Option[String], hakuOid: String) =
    ListFullSearchDto(aoOids = hakukohdeOid.toList, asIds = List(hakuOid), keys = commonKeys ++ List(
      "answers.koulutustausta.suoritusoikeus_tai_aiempi_tutkinto",
      "answers.koulutustausta.suoritusoikeus_tai_aiempi_tutkinto_vuosi"
    ))

  def hetuPersonOid(hakuOid: String) =
    ListFullSearchDto(asIds = List(hakuOid), keys = commonKeys ++ List(
      "answers.henkilotiedot.Henkilotunnus"
    ))
}

trait IHakemusService {
  def hakemuksetForPerson(personOid: String): Future[Seq[HakijaHakemus]]
  def hakemuksetForHakukohde(hakukohdeOid: String, organisaatio: Option[String]): Future[Seq[HakijaHakemus]]
  def hakemuksetForHakukohdes(hakukohdeOid: Set[String], organisaatio: Option[String]): Future[Seq[HakijaHakemus]]
  def personOidsForHaku(hakuOid: String, organisaatio: Option[String]): Future[Set[String]]
  def personOidsForHakukohde(hakukohdeOid: String, organisaatio: Option[String]): Future[Set[String]]
  def hakemuksetForHaku(hakuOid: String, organisaatio: Option[String]): Future[Seq[HakijaHakemus]]
  def suoritusoikeudenTaiAiemmanTutkinnonVuosi(hakuOid: String, hakukohdeOid: Option[String]): Future[Seq[HakijaHakemus]]
  def hakemuksetForPersonsInHaku(personOids: Set[String], hakuOid: String): Future[Seq[HakijaHakemus]]
  def addTrigger(trigger: Trigger): Unit
  def reprocessHaunHakemukset(hakuOid: String): Unit
  def hetuAndPersonOidForHaku(hakuOid: String): Future[Seq[HetuPersonOid]]
}

class HakemusService(hakuappRestClient: VirkailijaRestClient,
                     ataruHakemusClient: VirkailijaRestClient,
                     tarjontaActor: ActorRef,
                     organisaatioActor: ActorRef,
                     oppijaNumeroRekisteri: IOppijaNumeroRekisteri, pageSize: Int = 200)
                    (implicit val system: ActorSystem) extends IHakemusService {
  val fetchPersonAliases: (Seq[FullHakemus]) => Future[(Seq[FullHakemus], PersonOidsWithAliases)] = { hs: Seq[FullHakemus] =>
    val personOids: Seq[String] = hs.flatMap(_.personOid)
    oppijaNumeroRekisteri.enrichWithAliases(personOids.toSet).map((hs, _))
  }

  case class SearchParams(aoOids: Seq[String] = null, asId: String = null, organizationFilter: String = null,
                          updatedAfter: String = null, start: Int = 0, rows: Int = pageSize)

  private val logger = Logging.getLogger(system, this)
  var triggers: Seq[Trigger] = Seq()
  implicit val defaultTimeout: Timeout = 120.seconds

  def enrichAtaruHakemukset(hakemukset: Seq[AtaruHakemusDto], henkilot: Seq[Henkilo]): Future[Seq[AtaruHakemus]] = {
    def henkilotiedotFromHenkilo(hakemus: AtaruHakemusDto): Option[HakemusHenkilotiedot] = {
      henkilot.find(_.oidHenkilo == hakemus.personOid.getOrElse("")) match {
        case Some(henkilo) =>
          Option(HakemusHenkilotiedot(
            Henkilotunnus = henkilo.hetu,
            Etunimet = henkilo.etunimet,
            Sukunimi = henkilo.sukunimi,
            Kutsumanimi = henkilo.kutsumanimi,
            kotikunta = henkilo.kotikunta))
        case _ => None
      }
    }

    def getHakukohdeTarjoajaOid(hakukohdeOid: String): Future[Option[String]] = {
      (tarjontaActor ? HakukohdeQuery(hakukohdeOid)).mapTo[Option[Hakukohde]].map(_.flatMap(_.tarjoajaOids.flatMap(_.headOption)))
    }

    Future.sequence(hakemukset.map(hakemus => {
      val henkilotiedot: Option[HakemusHenkilotiedot] = henkilotiedotFromHenkilo(hakemus)
      val hetu: Option[String] = henkilotiedot.flatMap(_.Henkilotunnus)
      val answers: Option[HakemusAnswers] = Option(HakemusAnswers(henkilotiedot = henkilotiedot))
      val hakutoiveet: Future[List[HakutoiveDTO]] = Future.sequence(hakemus.hakukohteet.zipWithIndex.map {
        case (hakukohdeOid: String, index: Int) =>
          for {
            organizationOid: Option[String] <- getHakukohdeTarjoajaOid(hakukohdeOid)
            organization: Option[Organisaatio] <- organizationOid.map(o => {
              (organisaatioActor ? o).mapTo[Option[Organisaatio]]
            }).getOrElse(Future.successful(None))
          } yield HakutoiveDTO(index, Some(hakukohdeOid), None, None, None, organizationOid, organization.flatMap(_.parentOidPath.map(_.replace("/", ","))), None, None, None, None, None)
      }.toList)
      hakutoiveet.map(toiveet =>
        AtaruHakemus(hakemus.oid, hakemus.personOid, hakemus.applicationSystemId, answers, Some(toiveet), hakemus.kieli, hetu)
      )
    }))
  }

  private def getAtaruhakemuksetForPerson(personOid: String): Future[Seq[HakijaHakemus]] = {
    for {
      ataruHakemusDtos: Seq[AtaruHakemusDto] <- ataruHakemusClient
        .readObject[List[AtaruHakemusDto]]("ataru.applications", Map("hakijaOids" -> personOid))(acceptedResponseCode = 200, maxRetries = 2)
      ataruHenkilot: Seq[Henkilo] <- oppijaNumeroRekisteri.getByOids(ataruHakemusDtos.flatMap(_.personOid).toSet)
      ataruHakemukset: Seq[HakijaHakemus] <- enrichAtaruHakemukset(ataruHakemusDtos, ataruHenkilot)
    } yield ataruHakemukset
  }

  def hakemuksetForPerson(personOid: String): Future[Seq[HakijaHakemus]] = {
    for {
      hakuappHakemukset: Map[String, Seq[FullHakemus]] <- hakuappRestClient
        .postObject[Set[String], Map[String, Seq[FullHakemus]]]("haku-app.bypersonoid")(200, Set(personOid))
      ataruHakemukset: Seq[HakijaHakemus] <- getAtaruhakemuksetForPerson(personOid)
    } yield hakuappHakemukset.getOrElse(personOid, Seq[FullHakemus]()) ++ ataruHakemukset
  }

  def hakemuksetForPersonsInHaku(personOids: Set[String], hakuOid: String): Future[Seq[FullHakemus]] = {
    for (
      hakemuksetByPerson <- hakuappRestClient.postObject[Set[String], Map[String, Seq[FullHakemus]]]("haku-app.bypersonoid")(200, personOids)
    ) yield hakemuksetByPerson.values.flatten.filter(_.applicationSystemId == hakuOid).toSeq
  }

  def hakemuksetForHakukohde(hakukohdeOid: String, organisaatio: Option[String]): Future[Seq[HakijaHakemus]] = {
    fetchHakemukset(params = SearchParams(aoOids = Seq(hakukohdeOid), organizationFilter = organisaatio.orNull))
  }
  def hakemuksetForHakukohdes(hakukohdeOids: Set[String], organisaatio: Option[String]): Future[Seq[HakijaHakemus]] = {
    if(hakukohdeOids.isEmpty) {
      Future.successful(Seq())
    } else {
      fetchHakemukset(params = SearchParams(aoOids = hakukohdeOids.toSeq, organizationFilter = organisaatio.orNull))
    }
  }
  def hakemuksetForHaku(hakuOid: String, organisaatio: Option[String]): Future[Seq[FullHakemus]] = {
    fetchHakemukset(params = SearchParams(asId = hakuOid, organizationFilter = organisaatio.orNull))
  }

  def suoritusoikeudenTaiAiemmanTutkinnonVuosi(hakuOid: String, hakukohdeOid: Option[String]): Future[Seq[FullHakemus]] = {
    hakuappRestClient.postObject[ListFullSearchDto, List[FullHakemus]]("haku-app.listfull")(acceptedResponseCode = 200,
      ListFullSearchDto.suoritusvuosi(hakukohdeOid, hakuOid))
  }

  def personOidsForHaku(hakuOid: String, organisaatio: Option[String]): Future[Set[String]] = {
    hakuappRestClient.postObject[Set[String], Set[String]]("haku-app.personoidsbyapplicationsystem", organisaatio.orNull)(200, Set(hakuOid))
  }

  def personOidsForHakukohde(hakukohdeOid: String, organisaatio: Option[String]): Future[Set[String]] = {
    hakuappRestClient.postObject[Set[String], Set[String]]("haku-app.personoidsbyapplicationoption", organisaatio.orNull)(200, Set(hakukohdeOid))
  }

  def hetuAndPersonOidForHaku(hakuOid: String): Future[Seq[HetuPersonOid]] =
    hakuappRestClient.postObject[ListFullSearchDto, List[FullHakemus]]("haku-app.listfull")(acceptedResponseCode = 200,
      ListFullSearchDto.hetuPersonOid(hakuOid)).flatMap { hakemukset =>
        Future {
          hakemukset
            .filter(hakemus => hakemus.hetu.isDefined && hakemus.personOid.isDefined)
            .map(hakemus => HetuPersonOid(hakemus.hetu.get, hakemus.personOid.get))
        }
      }

  def addTrigger(trigger: Trigger) = triggers = triggers :+ trigger

  def reprocessHaunHakemukset(hakuOid: String): Unit = {
    hakemuksetForHaku(hakuOid, None).flatMap(fetchPersonAliases).onComplete {
      case Success((hakemukset, personOidsWithAliases)) =>
        logger.info(s"Reprocessing ${hakemukset.size} hakemus of haku $hakuOid")
        triggerHakemukset(hakemukset, personOidsWithAliases)
        logger.info(s"Reprocessed ${hakemukset.size} hakemus of haku $hakuOid")
      case Failure(t) =>
        logger.error(t, s"Failed to reprocess hakemukset of haku $hakuOid")
    }
  }

  def processModifiedHakemukset(modifiedAfter: Date = new Date(Platform.currentTime - TimeUnit.DAYS.toMillis(2)),
                                refreshFrequency: FiniteDuration = 1.minute)(implicit scheduler: Scheduler): Unit = {
    scheduler.scheduleOnce(refreshFrequency)({
      val lastChecked = new Date()
      fetchHakemukset(
        params = SearchParams(updatedAfter = new SimpleDateFormat("yyyyMMddHHmm").format(modifiedAfter))
      ).flatMap(fetchPersonAliases).onComplete {
        case Success((hakemukset, personOidsWithAliases)) =>
          Try(triggerHakemukset(hakemukset, personOidsWithAliases)) match {
            case Failure(e) => logger.error(e, "Exception in trigger!")
            case _ =>
          }
          processModifiedHakemukset(lastChecked, refreshFrequency)
        case Failure(t) =>
          logger.error(t, "Fetching modified hakemukset failed, retrying")
          processModifiedHakemukset(modifiedAfter, refreshFrequency)
      }
    })
  }

  private def triggerHakemukset(hakemukset: Seq[FullHakemus], personOidsWithAliases: PersonOidsWithAliases) =
    hakemukset.foreach(hakemus =>
      triggers.foreach(trigger => trigger.f(hakemus, personOidsWithAliases))
    )

  private def fetchHakemukset(page: Int = 0, params: SearchParams): Future[Seq[FullHakemus]] = {
    hakuappRestClient.readObject[List[FullHakemus]]("haku-app.listfull", params.copy(start = page * pageSize))(acceptedResponseCode = 200, maxRetries = 2)
      .flatMap(hakemukset =>
        if (hakemukset.length < pageSize) {
          Future.successful(hakemukset)
        } else {
          fetchHakemukset(page + 1, params).map(hakemukset ++ _)
        })
  }
}

class HakemusServiceMock extends IHakemusService {
  override def hakemuksetForPerson(personOid: String) = Future.successful(Seq[FullHakemus]())

  override def hakemuksetForHakukohde(hakukohdeOid: String, organisaatio: Option[String]) = Future.successful(Seq[FullHakemus]())

  override def hakemuksetForHakukohdes(hakukohdeOids: Set[String], organisaatio: Option[String]) = Future.successful(Seq[FullHakemus]())

  override def personOidsForHaku(hakuOid: String, organisaatio: Option[String]) = Future.successful(Set[String]())

  override def personOidsForHakukohde(hakukohdeOid: String, organisaatio: Option[String]) = Future.successful(Set[String]())

  override def hakemuksetForHaku(hakuOid: String, organisaatio: Option[String]) = Future.successful(Seq[FullHakemus]())

  override def suoritusoikeudenTaiAiemmanTutkinnonVuosi(hakuOid: String, hakukohdeOid: Option[String]): Future[Seq[FullHakemus]] = Future.successful(Seq[FullHakemus]())

  override def hakemuksetForPersonsInHaku(personOids: Set[String], hakuOid: String) = Future.successful(Seq[FullHakemus]())

  override def addTrigger(trigger: Trigger): Unit = ()

  override def reprocessHaunHakemukset(hakuOid: String): Unit = ()

  override def hetuAndPersonOidForHaku(hakuOid: String) = Future.successful(Seq[HetuPersonOid]())
}
