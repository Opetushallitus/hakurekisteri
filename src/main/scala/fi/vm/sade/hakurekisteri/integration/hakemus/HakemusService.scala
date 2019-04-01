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
import fi.vm.sade.hakurekisteri.integration.organisaatio.{Organisaatio, OrganisaatioActorRef}
import fi.vm.sade.hakurekisteri.integration.tarjonta.{Hakukohde, HakukohdeQuery, TarjontaActorRef}
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

case class Trigger(f: (HakijaHakemus, PersonOidsWithAliases) => Unit)

object Trigger {
  def apply(f: (String, String, String, PersonOidsWithAliases) => Unit): Trigger = {
    def processHakemusWithPersonOid(hakemus: HakijaHakemus, personOidsWithAliases: PersonOidsWithAliases): Unit = hakemus match {
      case FullHakemus(_, Some(personOid), applicationSystemId, _, _, _, _) if hakemus.hetu.isDefined =>
        f(personOid, hakemus.hetu.get, applicationSystemId, personOidsWithAliases)
      case h: AtaruHakemus if h.personOid.isDefined && h.hetu.isDefined =>
        f(h.personOid.get, h.hetu.get, h.applicationSystemId, personOidsWithAliases)
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
                     tarjontaActor: TarjontaActorRef,
                     organisaatioActor: OrganisaatioActorRef,
                     oppijaNumeroRekisteri: IOppijaNumeroRekisteri, pageSize: Int = 200)
                    (implicit val system: ActorSystem) extends IHakemusService {

  case class SearchParams(aoOids: Seq[String] = null, asId: String = null, organizationFilter: String = null,
                          updatedAfter: String = null, start: Int = 0, rows: Int = pageSize)

  case class AtaruSearchParams(hakijaOids: Option[List[String]],
                               hakukohdeOids: Option[List[String]],
                               hakuOid: Option[String],
                               organizationOid: Option[String],
                               modifiedAfter: Option[String])


  private val logger = Logging.getLogger(system, this)
  var triggers: Seq[Trigger] = Seq()
  implicit val defaultTimeout: Timeout = 120.seconds

  def enrichAtaruHakemukset(ataruHakemusDtos: List[AtaruHakemusDto], henkilot: Seq[Henkilo]): Future[List[AtaruHakemus]] = {
    val henkilotByOid = henkilot.map(h => h.oidHenkilo -> h).toMap

    def hakukohteenTarjoajaOid(hakukohdeOid: String): Future[String] = for {
      hakukohde <- (tarjontaActor.actor ? HakukohdeQuery(hakukohdeOid)).mapTo[Option[Hakukohde]].flatMap {
        case Some(h) => Future.successful(h)
        case None => Future.failed(new RuntimeException(s"Could not find hakukohde $hakukohdeOid"))
      }
      tarjoajaOid <- hakukohde.tarjoajaOids.flatMap(_.headOption) match {
        case Some(oid) => Future.successful(oid)
        case None => Future.failed(new RuntimeException(s"Could not find tarjoaja for hakukohde ${hakukohde.oid}"))
      }
    } yield tarjoajaOid

    def tarjoajanParentOids(tarjoajaOid: String): Future[Set[String]] = for {
      organisaatio <- (organisaatioActor.actor ? tarjoajaOid).mapTo[Option[Organisaatio]].flatMap {
        case Some(o) => Future.successful(o)
        case None => Future.failed(new RuntimeException(s"Could not find tarjoaja $tarjoajaOid"))
      }
      parentOids <- organisaatio.parentOidPath match {
        case Some(path) =>
          val parentOids = path.replace("/", "|").split("\\|").filterNot(_.isEmpty).toSet
          if (parentOids.forall(Organisaatio.isOrganisaatioOid)) {
            Future.successful(parentOids + organisaatio.oid)
          } else {
            Future.failed(new RuntimeException(s"Could not parse parent oids $path of organization ${organisaatio.oid}"))
          }
        case None => Future.successful(Set.empty[String])
      }
    } yield parentOids

    def translateAtaruMaksuvelvollisuus(hakemus: AtaruHakemusDto): Map[String, String] = hakemus.paymentObligations.mapValues {
      case "obligated" => "REQUIRED"
      case "not-obligated" => "NOT_REQUIRED"
      case "unreviewed" => "NOT_CHECKED"
      case s => throw new IllegalArgumentException(s"Unknown maksuvelvollisuus state $s on application ${hakemus.oid}")
    }

    def translateAtaruHakukelpoisuus(hakemus: AtaruHakemusDto): Map[String, String] = hakemus.eligibilities.mapValues {
      case "eligible" => "ELIGIBLE"
      case "uneligible" => "INELIGIBLE"
      case "unreviewed" => "NOT_CHECKED"
      case "conditionally-eligible" => "CONDITIONALLY_ELIGIBLE"
      case "automatically-checked-eligible" => "AUTOMATICALLY_CHECKED_ELIGIBLE"
      case s => throw new IllegalArgumentException(s"Unknown hakukelpoisuus state $s on application ${hakemus.oid}")
    }

    Future.sequence(
      ataruHakemusDtos
        .flatMap(_.hakukohteet)
        .distinct
        .map(hakukohdeOid => {
          val tarjoajaOid = hakukohteenTarjoajaOid(hakukohdeOid)
          Future.successful(hakukohdeOid).zip(tarjoajaOid.zip(tarjoajaOid.flatMap(tarjoajanParentOids)))
        })
    ).map(_.toMap).map(tarjoajaAndParentOids => ataruHakemusDtos.map(hakemus => {
      val hakutoiveet = hakemus.hakukohteet.zipWithIndex.map {
        case (hakukohdeOid, index) =>
          val (tarjoajaOid, parentOids) = tarjoajaAndParentOids(hakukohdeOid)
          HakutoiveDTO(index, Some(hakukohdeOid), None, None, None, Some(tarjoajaOid), parentOids, None, None, None, None, None)
      }
      AtaruHakemus(
        oid = hakemus.oid,
        personOid = Some(hakemus.personOid),
        applicationSystemId = hakemus.applicationSystemId,
        hakutoiveet = Some(hakutoiveet),
        henkilo = henkilotByOid(hakemus.personOid),
        email = hakemus.email,
        matkapuhelin = hakemus.matkapuhelin,
        lahiosoite = hakemus.lahiosoite,
        postinumero = hakemus.postinumero,
        postitoimipaikka = hakemus.postitoimipaikka,
        kotikunta = hakemus.kotikunta.map(s => if (s.length == 3 && s.forall(Character.isDigit)) s else "999"), // HLE-377
        asuinmaa = hakemus.asuinmaa,
        julkaisulupa = hakemus.valintatuloksenJulkaisulupa,
        markkinointilupa = hakemus.koulutusmarkkinointilupa,
        paymentObligations = translateAtaruMaksuvelvollisuus(hakemus),
        eligibilities = translateAtaruHakukelpoisuus(hakemus),
        kkPohjakoulutus = hakemus.kkPohjakoulutus,
        korkeakoulututkintoVuosi = hakemus.korkeakoulututkintoVuosi
      )
    }))
  }

  private def ataruhakemukset(params: AtaruSearchParams): Future[List[HakijaHakemus]] = {
    val p = params.hakuOid.fold[Map[String, Any]](Map.empty)(oid => Map("hakuOid" -> oid)) ++
      params.hakukohdeOids.fold[Map[String, Any]](Map.empty)(hakukohdeOids => Map("hakukohdeOids" -> hakukohdeOids)) ++
      params.hakijaOids.fold[Map[String, Any]](Map.empty)(hakijaOids => Map("hakijaOids" -> hakijaOids)) ++
      params.modifiedAfter.fold[Map[String, Any]](Map.empty)(date => Map("modifiedAfter" -> date))
    def page(offset: Option[String]): Future[(List[HakijaHakemus], Option[String])] = {
      for {
        ataruResponse <- ataruHakemusClient.postObjectWithCodes[Map[String, Any], AtaruResponse](
          uriKey = "ataru.applications",
          acceptedResponseCodes = List(200),
          maxRetries = 2,
          resource = offset.fold(p)(o => p + ("offset" -> o)),
          basicAuth = false
        )
        ataruHenkilot <- oppijaNumeroRekisteri.getByOids(ataruResponse.applications.map(_.personOid).toSet)
        ataruHakemukset <- enrichAtaruHakemukset(ataruResponse.applications, ataruHenkilot)
      } yield (params.organizationOid.fold(ataruHakemukset)(oid => ataruHakemukset.filter(hasAppliedToOrganization(_, oid))), ataruResponse.offset)
    }
    def allPages(offset: Option[String], acc: Future[List[HakijaHakemus]]): Future[List[HakijaHakemus]] = page(offset).flatMap {
      case (applications, None) => acc.map(_ ++ applications)
      case (applications, newOffset) => allPages(newOffset, acc.map(_ ++ applications))
    }
    allPages(None, Future.successful(List.empty))
  }

  private def hasAppliedToOrganization(hakemus: HakijaHakemus, organisaatio: String): Boolean =
    hakemus.hakutoiveet.exists(_.exists(_.organizationParentOids.contains(organisaatio)))

  def hakemuksetForPerson(personOid: String): Future[Seq[HakijaHakemus]] = {
    for {
      hakuappHakemukset: Map[String, Seq[FullHakemus]] <- hakuappRestClient
        .postObject[Set[String], Map[String, Seq[FullHakemus]]]("haku-app.bypersonoid")(200, Set(personOid))
      ataruHakemukset: Seq[HakijaHakemus] <- ataruhakemukset(AtaruSearchParams(
        hakijaOids = Some(List(personOid)),
        hakukohdeOids = None,
        hakuOid = None,
        organizationOid = None,
        modifiedAfter = None
      ))
    } yield hakuappHakemukset.getOrElse(personOid, Seq[FullHakemus]()) ++ ataruHakemukset
  }

  def hakemuksetForPersonsInHaku(personOids: Set[String], hakuOid: String): Future[Seq[HakijaHakemus]] = {
    for {
      hakuappHakemukset <- hakuappRestClient.postObject[Set[String], Map[String, Seq[FullHakemus]]]("haku-app.bypersonoid")(200, personOids)
        .map(_.values.flatten.filter(_.applicationSystemId == hakuOid).toSeq)
      ataruHakemukset <- ataruhakemukset(AtaruSearchParams(
        hakijaOids = Some(personOids.toList),
        hakukohdeOids = None,
        hakuOid = Some(hakuOid),
        organizationOid = None,
        modifiedAfter = None
      ))
    } yield hakuappHakemukset ++ ataruHakemukset
  }

  def hakemuksetForHakukohde(hakukohdeOid: String, organisaatio: Option[String]): Future[Seq[HakijaHakemus]] = {
    for {
      hakuappHakemukset <- fetchHakemuksetChunked(params = SearchParams(aoOids = Seq(hakukohdeOid), organizationFilter = organisaatio.orNull))
      ataruHakemukset <- ataruhakemukset(AtaruSearchParams(
        hakijaOids = None,
        hakukohdeOids = Some(List(hakukohdeOid)),
        hakuOid = None,
        organizationOid = organisaatio,
        modifiedAfter = None
      ))
    } yield hakuappHakemukset ++ ataruHakemukset
  }

  def hakemuksetForHakukohdes(hakukohdeOids: Set[String], organisaatio: Option[String]): Future[Seq[HakijaHakemus]] = {
    if (hakukohdeOids.isEmpty) {
      Future.successful(Seq())
    } else {
      for {
        hakuappHakemukset <- fetchHakemuksetChunked(params = SearchParams(aoOids = hakukohdeOids.toSeq, organizationFilter = organisaatio.orNull))
        ataruHakemukset <- ataruhakemukset(AtaruSearchParams(
          hakijaOids = None,
          hakukohdeOids = Some(hakukohdeOids.toList),
          hakuOid = None,
          organizationOid = organisaatio,
          modifiedAfter = None
        ))
      } yield hakuappHakemukset ++ ataruHakemukset
    }
  }
  def hakemuksetForHaku(hakuOid: String, organisaatio: Option[String]): Future[Seq[HakijaHakemus]] = {
    for {
      hakuappHakemukset <- fetchHakemuksetChunked(params = SearchParams(asId = hakuOid, organizationFilter = organisaatio.orNull))
      ataruHakemukset <- ataruhakemukset(AtaruSearchParams(
        hakijaOids = None,
        hakukohdeOids = None,
        hakuOid = Some(hakuOid),
        organizationOid = organisaatio,
        modifiedAfter = None
      ))
    } yield hakuappHakemukset ++ ataruHakemukset
  }

  def suoritusoikeudenTaiAiemmanTutkinnonVuosi(hakuOid: String, hakukohdeOid: Option[String]): Future[Seq[HakijaHakemus]] = {
    for {
      hakuappHakemukset <- hakuappRestClient.postObject[ListFullSearchDto, List[FullHakemus]]("haku-app.listfull")(acceptedResponseCode = 200,
        ListFullSearchDto.suoritusvuosi(hakukohdeOid, hakuOid))
      ataruHakemukset <- ataruhakemukset(AtaruSearchParams(
        hakijaOids = None,
        hakukohdeOids = hakukohdeOid.map(List(_)),
        hakuOid = Some(hakuOid),
        organizationOid = None,
        modifiedAfter = None
      ))
    } yield hakuappHakemukset ++ ataruHakemukset
  }

  def personOidsForHaku(hakuOid: String, organisaatio: Option[String]): Future[Set[String]] = {
    for {
      hakuappPersonOids <- hakuappRestClient.postObject[Set[String], Set[String]]("haku-app.personoidsbyapplicationsystem", organisaatio.orNull)(200, Set(hakuOid))
      ataruHakemukset <- ataruhakemukset(AtaruSearchParams(
        hakijaOids = None,
        hakukohdeOids = None,
        hakuOid = Some(hakuOid),
        organizationOid = organisaatio,
        modifiedAfter = None
      ))
    } yield hakuappPersonOids ++ ataruHakemukset.flatMap(_.personOid)
  }

  def personOidsForHakukohde(hakukohdeOid: String, organisaatio: Option[String]): Future[Set[String]] = {
    for {
      hakuappPersonOids <- hakuappRestClient.postObject[Set[String], Set[String]]("haku-app.personoidsbyapplicationoption", organisaatio.orNull)(200, Set(hakukohdeOid))
      ataruHakemukset <- ataruhakemukset(AtaruSearchParams(
        hakijaOids = None,
        hakukohdeOids = Some(List(hakukohdeOid)),
        hakuOid = None,
        organizationOid = organisaatio,
        modifiedAfter = None
      ))
    } yield hakuappPersonOids ++ ataruHakemukset.flatMap(_.personOid)
  }

  def hetuAndPersonOidForHaku(hakuOid: String): Future[Seq[HetuPersonOid]] = {
    for {
      hakuappHakemukset <- hakuappRestClient.postObject[ListFullSearchDto, List[FullHakemus]]("haku-app.listfull")(acceptedResponseCode = 200,
        ListFullSearchDto.hetuPersonOid(hakuOid))
      ataruHakemukset <- ataruhakemukset(AtaruSearchParams(
        hakijaOids = None,
        hakukohdeOids = None,
        hakuOid = Some(hakuOid),
        organizationOid = None,
        modifiedAfter = None
      ))
    } yield (hakuappHakemukset ++ ataruHakemukset).collect({
      case h: FullHakemus if h.hetu.isDefined && h.personOid.isDefined =>
        HetuPersonOid(hetu = h.hetu.get, personOid = h.personOid.get)
      case h: AtaruHakemus if h.henkilo.hetu.isDefined =>
        HetuPersonOid(hetu = h.henkilo.hetu.get, personOid = h.henkilo.oidHenkilo)
    })
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
      val formattedDate = new SimpleDateFormat("yyyyMMddHHmm").format(modifiedAfter)
      val allApplications: Future[List[HakijaHakemus]] = for {
        hakuappApplications: Seq[FullHakemus] <- fetchHakemuksetChunked(
          params = SearchParams(updatedAfter = formattedDate)
        )
        ataruApplications: List[HakijaHakemus] <- ataruhakemukset(AtaruSearchParams(None, None, None, None, Some(formattedDate)))
      } yield hakuappApplications.toList ::: ataruApplications
      allApplications.flatMap(fetchPersonAliases).onComplete {
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

  private def fetchPersonAliases(hs: Seq[HakijaHakemus]): Future[(Seq[HakijaHakemus], PersonOidsWithAliases)] = {
    oppijaNumeroRekisteri.enrichWithAliases(hs.flatMap(_.personOid).toSet).map((hs, _))
  }

  private def triggerHakemukset(hakemukset: Seq[HakijaHakemus], personOidsWithAliases: PersonOidsWithAliases): Unit =
    hakemukset.collect({ case h: HakijaHakemus => h }).foreach(hakemus =>
      triggers.foreach(trigger => trigger.f(hakemus, personOidsWithAliases))
    )

  private def fetchHakemuksetChunked(params: SearchParams): Future[Seq[FullHakemus]] = {
    val maxOidsChunkSize = 150
    if (params.aoOids == null) {
      fetchHakemukset(params = params)
    } else {
      val oidsChunks: Seq[Seq[String]] = params.aoOids.grouped(maxOidsChunkSize).toSeq
      val futures: Seq[Future[Seq[FullHakemus]]] = oidsChunks.map { oids =>
        val paramsForChunk = params.copy(aoOids = oids)
        fetchHakemukset(params = paramsForChunk)
      }
      Future.sequence(futures).map(_.flatten)
    }
  }

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
