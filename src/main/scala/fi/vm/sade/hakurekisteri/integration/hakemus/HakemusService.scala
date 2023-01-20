package fi.vm.sade.hakurekisteri.integration.hakemus

import akka.actor.{ActorSystem, Scheduler}
import akka.event.Logging
import akka.pattern.ask
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.{Config, integration}
import fi.vm.sade.hakurekisteri.hakija.HakijaQuery
import fi.vm.sade.hakurekisteri.integration.cache.{CacheFactory, RedisCache}
import fi.vm.sade.hakurekisteri.integration.hakukohde.{
  Hakukohde,
  HakukohdeAggregatorActorRef,
  HakukohdeQuery,
  HakukohteenKoulutuksetQuery
}
import fi.vm.sade.hakurekisteri.integration.henkilo.{
  Henkilo,
  IOppijaNumeroRekisteri,
  LinkedHenkiloOids,
  PersonOidsWithAliases
}
import fi.vm.sade.hakurekisteri.integration.kouta.{
  HakukohteetHaussaQuery,
  KoutaInternalActorRef,
  KoutaInternalHakukohdeLite
}
import fi.vm.sade.hakurekisteri.integration.organisaatio.{Organisaatio, OrganisaatioActorRef}
import fi.vm.sade.hakurekisteri.integration.{OphUrlProperties, ServiceConfig, VirkailijaRestClient}
import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriJsonSupport, Query}
import fi.vm.sade.properties.OphProperties
import org.json4s.jackson.JsonMethods
import org.json4s.jackson.Serialization.write

import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import scala.compat.Platform
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

case class HakemusConfig(serviceConf: ServiceConfig, maxApplications: Int)

case class HakemusQuery(
  haku: Option[String],
  organisaatio: Option[String] = None,
  hakukohdekoodi: Option[String] = None,
  hakukohde: Option[String] = None
) extends Query[FullHakemus]

case class HenkiloHakijaQuery(henkilo: String) extends Query[FullHakemus]

object HakemusQuery {
  def apply(hq: HakijaQuery): HakemusQuery =
    HakemusQuery(hq.haku, hq.organisaatio, hq.hakukohdekoodi)
}

case class Trigger(f: (HakijaHakemus, PersonOidsWithAliases) => Unit)

object Trigger {
  def apply(f: (String, String, String, PersonOidsWithAliases) => Unit): Trigger = {
    def processHakemusWithPersonOid(
      hakemus: HakijaHakemus,
      personOidsWithAliases: PersonOidsWithAliases
    ): Unit = hakemus match {
      case FullHakemus(_, Some(personOid), applicationSystemId, _, _, _, _, _, _)
          if hakemus.hetu.isDefined =>
        f(personOid, hakemus.hetu.get, applicationSystemId, personOidsWithAliases)
      case h: AtaruHakemus if h.personOid.isDefined && h.hetu.isDefined =>
        f(h.personOid.get, h.hetu.get, h.applicationSystemId, personOidsWithAliases)
      case _ =>
    }

    new Trigger(processHakemusWithPersonOid)
  }
}

case class HakemusHakuHetuPersonOid(hakemus: String, haku: String, hetu: String, personOid: String)
case class HetuPersonOid(hetu: String, personOid: String)

case class ListFullSearchDto(
  searchTerms: String = "",
  states: List[String] = List(),
  aoOids: List[String] = List(),
  asIds: List[String] = List(),
  personOids: Option[List[String]] = None,
  keys: List[String]
)

object ListFullSearchDto {
  val commonKeys = List("oid", "applicationSystemId", "personOid")

  def suoritusvuosi(hakukohdeOid: Option[String], hakuOid: String) =
    ListFullSearchDto(
      aoOids = hakukohdeOid.toList,
      asIds = List(hakuOid),
      keys = commonKeys ++ List(
        "answers.koulutustausta.suoritusoikeus_tai_aiempi_tutkinto",
        "answers.koulutustausta.suoritusoikeus_tai_aiempi_tutkinto_vuosi"
      )
    )

  def hetuPersonOid(hakuOid: String) =
    ListFullSearchDto(
      asIds = List(hakuOid),
      keys = commonKeys ++ List(
        "answers.henkilotiedot.Henkilotunnus"
      )
    )

  def hetuForPersonOid(personOid: String) =
    ListFullSearchDto(
      personOids = Some(List(personOid)),
      keys = commonKeys ++ List(
        "answers.henkilotiedot.Henkilotunnus"
      )
    )
}

trait IHakemusService {
  def hakemuksetForPerson(personOid: String): Future[Seq[HakijaHakemus]]
  def hakemuksetForPersons(personOids: Set[String]): Future[Seq[HakijaHakemus]]
  def personOidstoMasterOids(personOids: Set[String]): Future[Map[String, String]]
  def hakemuksetForHakukohde(
    hakukohdeOid: String,
    organisaatio: Option[String]
  ): Future[Seq[HakijaHakemus]]
  def hakemuksetForHakukohdes(
    hakukohdeOid: Set[String],
    organisaatio: Option[String]
  ): Future[Seq[HakijaHakemus]]
  def personOidsForHaku(hakuOid: String, organisaatio: Option[String]): Future[Set[String]]
  def personOidsForHakukohde(
    hakukohdeOid: String,
    organisaatio: Option[String]
  ): Future[Set[String]]
  def hakemuksetForHaku(hakuOid: String, organisaatio: Option[String]): Future[Seq[HakijaHakemus]]
  def hakemuksetForToisenAsteenAtaruHaku(
    hakuOid: String,
    organisaatio: Option[String],
    hakukohdekoodi: Option[String]
  ): Future[Seq[AtaruHakemusToinenAste]]
  def suoritusoikeudenTaiAiemmanTutkinnonVuosi(
    hakuOid: String,
    hakukohdeOid: Option[String]
  ): Future[Seq[HakijaHakemus]]
  def hakemuksetForPersonsInHaku(
    personOids: Set[String],
    hakuOid: String
  ): Future[Seq[HakijaHakemus]]
  def addTrigger(trigger: Trigger): Unit
  def reprocessHaunHakemukset(hakuOid: String): Unit
  def hetuAndPersonOidForHaku(hakuOid: String): Future[Seq[HetuPersonOid]]
  def hetuAndPersonOidForPersonOid(personOid: String): Future[Seq[HakemusHakuHetuPersonOid]]
}

case class AtaruSearchParams(
  hakijaOids: Option[List[String]],
  hakukohdeOids: Option[List[String]],
  hakuOid: Option[String],
  organizationOid: Option[String],
  modifiedAfter: Option[String]
)

class HakemusService(
  hakuappRestClient: VirkailijaRestClient,
  ataruHakemusClient: VirkailijaRestClient,
  hakukohdeAggregatorActor: HakukohdeAggregatorActorRef,
  koutaInternalActor: KoutaInternalActorRef,
  organisaatioActor: OrganisaatioActorRef,
  oppijaNumeroRekisteri: IOppijaNumeroRekisteri,
  config: Config,
  cacheFactory: CacheFactory,
  pageSize: Int = 400,
  maxOidsChunkSize: Int = 150
)(implicit val system: ActorSystem)
    extends IHakemusService {

  case class SearchParams(
    aoOids: Seq[String] = null,
    asId: String = null,
    organizationFilter: String = null,
    updatedAfter: String = null,
    start: Int = 0,
    rows: Int = pageSize
  )

  private val logger = Logging.getLogger(system, this)
  logger.info(s"Using page size $pageSize when fetching modified applications from haku-app.")
  var triggers: Seq[Trigger] = Seq()
  implicit val defaultTimeout: Timeout = 120.seconds

  @SerialVersionUID(1)
  case class AllHakemukset(
    hakuAppHakemukset: Seq[FullHakemus],
    ataruHakemukset: Seq[AtaruHakemus]
  ) extends Serializable {}

  private val hakemusCache =
    cacheFactory.getInstance[String, String](
      config.integrations.valpasHakemusRefreshTimeHours.hours.toMillis,
      this.getClass,
      classOf[String],
      "valpas-hakuappOrAtaruHakemus"
    )

  def trimKoodiToNumber(koodiUri: String): String = {
    koodiUri match {
      case koodi if koodi == null || koodi.isEmpty => ""
      case koodi if koodi.contains("_") =>
        val withVersion = koodi.split("_")(1)
        withVersion match {
          case k: String if k.contains("#") => k.split("#")(0)
          case k: String                    => k
        }
    }
  }

  def enrichAtaruHakemuksetToinenAste(
    ataruHakemusDtos: List[AtaruHakemusToinenAsteDto],
    henkilot: Map[String, Henkilo],
    skipResolvingTarjoaja: Boolean = false,
    hakuOid: String,
    koodisByOid: Map[String, Option[String]]
  ): Future[List[AtaruHakemusToinenAste]] = {
    def hakukohteenTarjoajaOid(hakukohdeOid: String): Future[String] = for {
      hakukohde <- (hakukohdeAggregatorActor.actor ? HakukohdeQuery(hakukohdeOid))
        .mapTo[Hakukohde]
      tarjoajaOid <- hakukohde.tarjoajaOids.flatMap(_.headOption) match {
        case Some(oid) => Future.successful(oid)
        case None =>
          Future.failed(
            new RuntimeException(
              s"Could not find tarjoaja for hakukohde ${hakukohde.oid}"
            )
          )
      }
    } yield tarjoajaOid

    def tarjoajanParentOids(tarjoajaOid: String): Future[Set[String]] = for {
      organisaatio <- (organisaatioActor.actor ? tarjoajaOid).mapTo[Option[Organisaatio]].flatMap {
        case Some(o) => Future.successful(o)
        case None    => Future.failed(new RuntimeException(s"Could not find tarjoaja $tarjoajaOid"))
      }
      parentOids <- organisaatio.parentOidPath match {
        case Some(path) =>
          val parentOids = path.replace("/", "|").split("\\|").filterNot(_.isEmpty).toSet
          if (parentOids.forall(Organisaatio.isOrganisaatioOid)) {
            Future.successful(parentOids + organisaatio.oid)
          } else {
            Future.failed(
              new RuntimeException(
                s"Could not parse parent oids $path of organization ${organisaatio.oid}"
              )
            )
          }
        case None => Future.successful(Set.empty[String])
      }
    } yield parentOids

    val hakemustenHakukohteet = ataruHakemusDtos.flatMap(_.hakukohteet).map(_.oid).distinct
    val resolveTarjoajaOids: Future[Map[String, (String, Set[String])]] =
      if (skipResolvingTarjoaja) {
        Future.successful(Map.empty)
      } else {
        Future
          .sequence(
            hakemustenHakukohteet
              .map(hakukohdeOid => {
                val tarjoajaOid = hakukohteenTarjoajaOid(hakukohdeOid)
                Future
                  .successful(hakukohdeOid)
                  .zip(tarjoajaOid.zip(tarjoajaOid.flatMap(tarjoajanParentOids)))
              })
          )
          .map(_.toMap)
      }

    val res = resolveTarjoajaOids
      .map(tarjoajaAndParentOids =>
        ataruHakemusDtos.map((hakemus: AtaruHakemusToinenAsteDto) => {
          val hakutoiveet = hakemus.hakukohteet.zipWithIndex.map { case (hakutoive, index) =>
            val tarjoaja = tarjoajaAndParentOids.get(hakutoive.oid)
            HakutoiveDTO(
              index + 1,
              Some(hakutoive.oid),
              koodisByOid.getOrElse(hakutoive.oid, None).map(koodi => trimKoodiToNumber(koodi)),
              None,
              None,
              tarjoaja.map(_._1),
              tarjoaja.map(_._2).getOrElse(Set.empty),
              hakutoive.kiinnostunutKaksoistutkinnosta.map(v => if (v) "true" else "false"),
              hakutoive.aiempiPeruminen.map(v => if (v) "true" else "false"),
              hakutoive.terveys.map(v => if (v) "true" else "false"),
              hakutoive.kiinnostunutUrheilijanAmmatillisestaKoulutuksesta.map(v =>
                if (v) "true" else "false"
              ),
              None
            )
          }
          val harkinnanvaraisuudet = hakemus.hakukohteet
            .map(hk => HakutoiveenHarkinnanvaraisuus(hk.oid, hk.harkinnanvaraisuus))

          AtaruHakemusToinenAste(
            oid = hakemus.oid,
            personOid = Some(hakemus.personOid), //not optional?
            createdTime = hakemus.createdTime,
            hakemusFirstSubmittedTime = hakemus.hakemusFirstSubmittedTime,
            asiointiKieli = hakemus.kieli,
            applicationSystemId = hakuOid,
            hakutoiveet = Some(hakutoiveet),
            henkilo = henkilot(hakemus.personOid),
            email = hakemus.email,
            matkapuhelin = hakemus.matkapuhelin,
            lahiosoite = hakemus.lahiosoite,
            postinumero = hakemus.postinumero,
            postitoimipaikka = hakemus.postitoimipaikka,
            kotikunta = hakemus.kotikunta,
            asuinmaa = hakemus.asuinmaa,
            attachments = hakemus.attachments,
            pohjakoulutus = hakemus.pohjakoulutus,
            kiinnostunutOppisopimusKoulutuksesta = hakemus.kiinnostunutOppisopimusKoulutuksesta,
            sahkoisenAsioinninLupa = hakemus.sahkoisenAsioinninLupa,
            valintatuloksenJulkaisulupa = hakemus.valintatuloksenJulkaisulupa,
            koulutusmarkkinointilupa = hakemus.koulutusmarkkinointilupa,
            tutkintoVuosi = hakemus.tutkintoVuosi,
            tutkintoKieli = hakemus.tutkintoKieli,
            huoltajat = hakemus.huoltajat,
            harkinnanvaraisuudet = harkinnanvaraisuudet,
            urheilijanLisakysymykset = hakemus.urheilijanLisakysymykset
          )
        })
      )
    res
  }

  def enrichAtaruHakemukset(
    ataruHakemusDtos: List[AtaruHakemusDto],
    henkilot: Map[String, Henkilo],
    skipResolvingTarjoaja: Boolean = false
  ): Future[List[AtaruHakemus]] = {
    def hakukohteenTarjoajaOid(hakukohdeOid: String): Future[String] = for {
      hakukohde <- (hakukohdeAggregatorActor.actor ? HakukohdeQuery(hakukohdeOid))
        .mapTo[Hakukohde]
      tarjoajaOid <- hakukohde.tarjoajaOids.flatMap(_.headOption) match {
        case Some(oid) => Future.successful(oid)
        case None =>
          Future.failed(
            new RuntimeException(
              s"Could not find tarjoaja for hakukohde ${hakukohde.oid}"
            )
          )
      }
    } yield tarjoajaOid

    def tarjoajanParentOids(tarjoajaOid: String): Future[Set[String]] = for {
      organisaatio <- (organisaatioActor.actor ? tarjoajaOid).mapTo[Option[Organisaatio]].flatMap {
        case Some(o) => Future.successful(o)
        case None    => Future.failed(new RuntimeException(s"Could not find tarjoaja $tarjoajaOid"))
      }
      parentOids <- organisaatio.parentOidPath match {
        case Some(path) =>
          val parentOids = path.replace("/", "|").split("\\|").filterNot(_.isEmpty).toSet
          if (parentOids.forall(Organisaatio.isOrganisaatioOid)) {
            Future.successful(parentOids + organisaatio.oid)
          } else {
            Future.failed(
              new RuntimeException(
                s"Could not parse parent oids $path of organization ${organisaatio.oid}"
              )
            )
          }
        case None => Future.successful(Set.empty[String])
      }
    } yield parentOids

    def translateAtaruMaksuvelvollisuus(hakemus: AtaruHakemusDto): Map[String, String] =
      hakemus.paymentObligations
        .mapValues {
          case "obligated"     => "REQUIRED"
          case "not-obligated" => "NOT_REQUIRED"
          case "unreviewed"    => "NOT_CHECKED"
          case s =>
            throw new IllegalArgumentException(
              s"Unknown maksuvelvollisuus state $s on application ${hakemus.oid}"
            )
        }
        .map(identity)

    def translateAtaruHakukelpoisuus(hakemus: AtaruHakemusDto): Map[String, String] =
      hakemus.eligibilities
        .mapValues {
          case "eligible"                       => "ELIGIBLE"
          case "uneligible"                     => "INELIGIBLE"
          case "unreviewed"                     => "NOT_CHECKED"
          case "conditionally-eligible"         => "CONDITIONALLY_ELIGIBLE"
          case "automatically-checked-eligible" => "AUTOMATICALLY_CHECKED_ELIGIBLE"
          case s =>
            throw new IllegalArgumentException(
              s"Unknown hakukelpoisuus state $s on application ${hakemus.oid}"
            )
        }
        .map(identity)

    def translateAtaruAttachments(hakemus: AtaruHakemusDto): Map[String, Option[Boolean]] =
      hakemus.attachments
        .mapValues {
          case "checked"     => Some(true)
          case "not-checked" => Some(false)
          case _             => None
        }
        .map(identity)

    val resolveTarjoajaOids: Future[Map[String, (String, Set[String])]] =
      if (skipResolvingTarjoaja) {
        Future.successful(Map.empty)
      } else {
        Future
          .sequence(
            ataruHakemusDtos
              .flatMap(_.hakukohteet)
              .distinct
              .map(hakukohdeOid => {
                val tarjoajaOid = hakukohteenTarjoajaOid(hakukohdeOid)
                Future
                  .successful(hakukohdeOid)
                  .zip(tarjoajaOid.zip(tarjoajaOid.flatMap(tarjoajanParentOids)))
              })
          )
          .map(_.toMap)
      }

    resolveTarjoajaOids
      .map(tarjoajaAndParentOids =>
        ataruHakemusDtos.map(hakemus => {
          val hakutoiveet = hakemus.hakukohteet.zipWithIndex.map { case (hakukohdeOid, index) =>
            val tarjoaja = tarjoajaAndParentOids.get(hakukohdeOid)
            HakutoiveDTO(
              index + 1,
              Some(hakukohdeOid),
              None,
              None,
              None,
              tarjoaja.map(_._1),
              tarjoaja.map(_._2).getOrElse(Set.empty),
              None,
              None,
              None,
              None,
              None
            )
          }
          AtaruHakemus(
            oid = hakemus.oid,
            personOid = Some(hakemus.personOid),
            applicationSystemId = hakemus.applicationSystemId,
            createdTime = hakemus.createdTime,
            hakutoiveet = Some(hakutoiveet),
            henkilo = henkilot(hakemus.personOid),
            asiointiKieli = hakemus.kieli,
            email = hakemus.email,
            matkapuhelin = hakemus.matkapuhelin,
            lahiosoite = hakemus.lahiosoite,
            postinumero = hakemus.postinumero,
            postitoimipaikka = hakemus.postitoimipaikka,
            kotikunta = hakemus.kotikunta
              .map(s => if (s.length == 3 && s.forall(Character.isDigit)) s else "999"), // HLE-377
            asuinmaa = hakemus.asuinmaa,
            julkaisulupa = hakemus.valintatuloksenJulkaisulupa,
            markkinointilupa = hakemus.koulutusmarkkinointilupa,
            paymentObligations = translateAtaruMaksuvelvollisuus(hakemus),
            eligibilities = translateAtaruHakukelpoisuus(hakemus),
            liitteetTarkastettu = translateAtaruAttachments(hakemus),
            kkPohjakoulutus = hakemus.kkPohjakoulutus,
            korkeakoulututkintoVuosi = hakemus.korkeakoulututkintoVuosi
          )
        })
      )
  }

  private def ataruhakemukset(
    params: AtaruSearchParams,
    skipResolvingTarjoaja: Boolean = false
  ): Future[List[AtaruHakemus]] = {
    val p = params.hakuOid.fold[Map[String, Any]](Map.empty)(oid => Map("hakuOid" -> oid)) ++
      params.hakukohdeOids.fold[Map[String, Any]](Map.empty)(hakukohdeOids =>
        Map("hakukohdeOids" -> hakukohdeOids)
      ) ++
      params.hakijaOids.fold[Map[String, Any]](Map.empty)(hakijaOids =>
        Map("hakijaOids" -> hakijaOids)
      ) ++
      params.modifiedAfter.fold[Map[String, Any]](Map.empty)(date => Map("modifiedAfter" -> date))
    def page(offset: Option[String]): Future[(List[AtaruHakemus], Option[String])] = {
      for {
        ataruResponse <- ataruHakemusClient
          .postObjectWithCodes[Map[String, Any], AtaruResponse](
            uriKey = "ataru.applications",
            acceptedResponseCodes = List(200),
            maxRetries = 2,
            resource = offset.fold(p)(o => p + ("offset" -> o)),
            basicAuth = false
          )
        ataruHenkilot <- oppijaNumeroRekisteri.getByOids(
          ataruResponse.applications
            .map(_.personOid)
            .toSet
        )
        ataruHakemukset <- enrichAtaruHakemukset(
          ataruResponse.applications,
          ataruHenkilot,
          skipResolvingTarjoaja
        )
      } yield (
        params.organizationOid.fold(ataruHakemukset)(oid =>
          ataruHakemukset.filter(hasAppliedToOrganization(_, oid))
        ),
        ataruResponse.offset
      )
    }
    def allPages(
      offset: Option[String],
      acc: Future[List[AtaruHakemus]]
    ): Future[List[AtaruHakemus]] = page(offset).flatMap {
      case (applications, None)      => acc.map(_ ++ applications)
      case (applications, newOffset) => allPages(newOffset, acc.map(_ ++ applications))
    }
    allPages(None, Future.successful(List.empty))
  }

  private def ataruhakemuksetToinenAste(
    params: AtaruSearchParams,
    koodisByOid: Map[String, Option[String]],
    skipResolvingTarjoaja: Boolean = false
  ): Future[List[AtaruHakemusToinenAste]] = {
    logger.info(
      s"Haetaan toisen asteen ataruhakemukset ${params.hakukohdeOids.map(hks => hks.size).getOrElse(0)} hakukohteelle : $params"
    )
    if (
      params.hakuOid.isEmpty || (params.hakukohdeOids
        .getOrElse(List.empty)
        .isEmpty && params.hakijaOids.getOrElse(List.empty).isEmpty)
    ) {
      logger.warning(s"Puutteelliset parametrit: $params, palautetaan tyhjä lista")
      Future.successful(List.empty)
    } else {
      val p: Map[String, Any] =
        params.hakukohdeOids.fold[Map[String, Any]](Map.empty)(hakukohdeOids =>
          Map("hakukohdeOids" -> hakukohdeOids)
        ) ++
          params.hakijaOids.fold[Map[String, Any]](Map.empty)(hakijaOids =>
            Map("hakijaOids" -> hakijaOids)
          ) ++
          params.modifiedAfter.fold[Map[String, Any]](Map.empty)(date =>
            Map("modifiedAfter" -> date)
          )
      def page(offset: Option[String]): Future[(List[AtaruHakemusToinenAste], Option[String])] = {
        for {
          ataruResponse <- ataruHakemusClient
            .postObjectWithCodes[Map[String, Any], AtaruResponseToinenAste](
              uriKey = "ataru.applications.toinenaste",
              acceptedResponseCodes = List(200),
              maxRetries = 2,
              resource = offset.fold(p)(o => p + ("offset" -> o)),
              basicAuth = false,
              params.hakuOid.get
            )
          ataruHenkilot <- oppijaNumeroRekisteri.getByOids(
            ataruResponse.applications
              .map(_.personOid)
              .toSet
          )
          ataruHakemukset <- enrichAtaruHakemuksetToinenAste(
            ataruResponse.applications,
            ataruHenkilot,
            skipResolvingTarjoaja,
            params.hakuOid.get,
            koodisByOid
          )
        } yield (
          params.organizationOid.fold(ataruHakemukset)(oid =>
            ataruHakemukset.filter(hasAppliedToOrganization(_, oid))
          ),
          ataruResponse.offset
        )
      }
      def allPages(
        offset: Option[String],
        acc: Future[List[AtaruHakemusToinenAste]]
      ): Future[List[AtaruHakemusToinenAste]] = page(offset).flatMap {
        case (applications, None)      => acc.map(_ ++ applications)
        case (applications, newOffset) => allPages(newOffset, acc.map(_ ++ applications))
      }
      allPages(None, Future.successful(List.empty))
    }
  }

  private def hasAppliedToOrganization(hakemus: HakijaHakemus, organisaatio: String): Boolean =
    hakemus.hakutoiveet.exists(_.exists(_.organizationParentOids.contains(organisaatio)))

  def personOidstoMasterOids(personOids: Set[String]): Future[Map[String, String]] = {
    def personOidToMasterOidLookup(oids: LinkedHenkiloOids) =
      personOids.map(oid => (oid, oids.oidToMasterOid.getOrElse(oid, oid))).toMap

    for {
      linkedHenkiloOids: LinkedHenkiloOids <- oppijaNumeroRekisteri.fetchLinkedHenkiloOidsMap(
        personOids
      )
    } yield personOidToMasterOidLookup(linkedHenkiloOids)
  }

  implicit val formats = HakurekisteriJsonSupport.format

  def hakemuksetForPersonsFromHakuappAndAtaru(
    personOids: Set[String]
  ): Future[Seq[HakijaHakemus]] = {
    val hakuAppCall = hakuappRestClient
      .postObject[Set[String], Map[String, Seq[FullHakemus]]]("haku-app.bypersonoid")(
        200,
        personOids
      )
    val ataruCall = ataruhakemukset(
      AtaruSearchParams(
        hakijaOids = Some(personOids.toList),
        hakukohdeOids = None,
        hakuOid = None,
        organizationOid = None,
        modifiedAfter = None
      )
    )

    def updateCache(hakemukset: Seq[HakijaHakemus]): Seq[HakijaHakemus] = {
      hakemukset.groupBy(_.personOid).foreach {
        case (Some(personOid), allHakemukset) =>
          val f: Seq[FullHakemus] = allHakemukset.flatMap {
            case f: FullHakemus => Some(f)
            case _              => None
          }
          val a: Seq[AtaruHakemus] = allHakemukset.flatMap {
            case f: AtaruHakemus => Some(f)
            case _               => None
          }
          try {
            val json: String = write(AllHakemukset(f, a))
            hakemusCache + (personOid, json)
          } catch {
            case e: Exception =>
              logger.error(s"Couldn't store $personOid hakemus to Redis cache", e)
          }
        case _ =>
        // dont care
      }
      hakemukset
    }

    val fetchAllHakemukset: Future[List[HakijaHakemus]] = for {
      hakuappHakemukset: Map[String, Seq[FullHakemus]] <- hakuAppCall
      ataruHakemukset: Seq[HakijaHakemus] <- ataruCall
    } yield hakuappHakemukset.values.toList.flatten ++ ataruHakemukset

    fetchAllHakemukset.onComplete {
      case Success(all) =>
        updateCache(all)
      case _ =>
      //
    }

    fetchAllHakemukset
  }

  def hakemuksetForPersons(personOids: Set[String]): Future[Seq[HakijaHakemus]] = {

    val cachedHakemukset: Future[Set[Option[AllHakemukset]]] = hakemusCache match {
      case redis: RedisCache[String, String] =>
        def parse(s: Option[String]): Option[AllHakemukset] = {
          s match {
            case Some(value) =>
              val v = JsonMethods.parse(value)
              val fulls = (v \ "hakuAppHakemukset").extract[Seq[FullHakemus]]
              val atarus = (v \ "ataruHakemukset").extract[Seq[AtaruHakemus]]
              Some(AllHakemukset(fulls, atarus))
            case None =>
              None
          }
        }
        Future.sequence(personOids.map(oid => redis.get(oid).map(parse)))
      case _ =>
        Future.successful(Set.empty[Option[AllHakemukset]])
    }

    for {
      cachedHakemukset: Set[Option[AllHakemukset]] <- cachedHakemukset
      foundHakemukset: Seq[HakijaHakemus] <- Future.successful(
        cachedHakemukset.toSeq.flatMap {
          case Some(all) => all.ataruHakemukset ++ all.hakuAppHakemukset
          case None      => Seq.empty
        }
      )
      missedHakemukset <- personOids.diff(foundHakemukset.flatMap(_.personOid).toSet) match {
        case s if s.isEmpty => Future.successful(Seq.empty[HakijaHakemus])
        case s              => hakemuksetForPersonsFromHakuappAndAtaru(s)
      }
    } yield foundHakemukset ++ missedHakemukset
  }

  def hakemuksetForPerson(personOid: String): Future[Seq[HakijaHakemus]] = {
    for {
      linkedHenkiloOids: LinkedHenkiloOids <- oppijaNumeroRekisteri.fetchLinkedHenkiloOidsMap(
        Set(personOid)
      )
      masterOid: String <- Future(linkedHenkiloOids.oidToMasterOid.getOrElse(personOid, personOid))
      hakuappHakemukset: Map[String, Seq[FullHakemus]] <- hakuappRestClient
        .postObject[Set[String], Map[String, Seq[FullHakemus]]]("haku-app.bypersonoid")(
          200,
          linkedHenkiloOids.oidToLinkedOids.getOrElse(personOid, Set())
        )
      ataruHakemukset: Seq[HakijaHakemus] <- ataruhakemukset(
        AtaruSearchParams(
          hakijaOids = Some(List(personOid)),
          hakukohdeOids = None,
          hakuOid = None,
          organizationOid = None,
          modifiedAfter = None
        )
      )
    } yield hakuappHakemukset.values.toList.flatten
      .map(_.copy(personOid = Some(masterOid))) ++ ataruHakemukset
  }

  def hakemuksetForPersonsInHaku(
    personOids: Set[String],
    hakuOid: String
  ): Future[Seq[HakijaHakemus]] = {
    for {
      hakuappHakemukset <- hakuappRestClient
        .postObject[Set[String], Map[String, Seq[FullHakemus]]]("haku-app.bypersonoid")(
          200,
          personOids
        )
        .map(_.values.flatten.filter(_.applicationSystemId == hakuOid).toSeq)
      ataruHakemukset <- ataruhakemukset(
        AtaruSearchParams(
          hakijaOids = Some(personOids.toList),
          hakukohdeOids = None,
          hakuOid = Some(hakuOid),
          organizationOid = None,
          modifiedAfter = None
        )
      )
    } yield hakuappHakemukset ++ ataruHakemukset
  }

  def hakemuksetForHakukohde(
    hakukohdeOid: String,
    organisaatio: Option[String]
  ): Future[Seq[HakijaHakemus]] = {

    for {
      hakuappHakemukset <- fetchHakemuksetChunked(params =
        SearchParams(aoOids = Seq(hakukohdeOid), organizationFilter = organisaatio.orNull)
      )
      ataruHakemukset <- ataruhakemukset(
        AtaruSearchParams(
          hakijaOids = None,
          hakukohdeOids = Some(List(hakukohdeOid)),
          hakuOid = None,
          organizationOid = organisaatio,
          modifiedAfter = None
        )
      )
    } yield hakuappHakemukset ++ ataruHakemukset
  }

  def hakemuksetForHakukohdes(
    hakukohdeOids: Set[String],
    organisaatio: Option[String]
  ): Future[Seq[HakijaHakemus]] = {
    if (hakukohdeOids.isEmpty) {
      Future.successful(Seq())
    } else {
      for {
        hakuappHakemukset <- fetchHakemuksetChunked(params =
          SearchParams(aoOids = hakukohdeOids.toSeq, organizationFilter = organisaatio.orNull)
        )
        ataruHakemukset <- ataruhakemukset(
          AtaruSearchParams(
            hakijaOids = None,
            hakukohdeOids = Some(hakukohdeOids.toList),
            hakuOid = None,
            organizationOid = organisaatio,
            modifiedAfter = None
          )
        )
      } yield hakuappHakemukset ++ ataruHakemukset
    }
  }
  def hakemuksetForHaku(
    hakuOid: String,
    organisaatio: Option[String]
  ): Future[Seq[HakijaHakemus]] = {
    for {
      hakuappHakemukset <- fetchHakemuksetChunked(params =
        SearchParams(asId = hakuOid, organizationFilter = organisaatio.orNull)
      )
      ataruHakemukset <- ataruhakemukset(
        AtaruSearchParams(
          hakijaOids = None,
          hakukohdeOids = None,
          hakuOid = Some(hakuOid),
          organizationOid = organisaatio,
          modifiedAfter = None
        )
      )
    } yield hakuappHakemukset ++ ataruHakemukset
  }

  //Käytännössä kannattaa määritellä joko organisaatio tai hakukohdekoodi, tai muuten haetaan kaikki haun hakemukset
  def hakemuksetForToisenAsteenAtaruHaku(
    hakuOid: String,
    organisaatio: Option[String],
    hakukohdekoodi: Option[String]
  ): Future[Seq[AtaruHakemusToinenAste]] = {
    val hakukohteidenTiedot: Future[List[KoutaInternalHakukohdeLite]] =
      (koutaInternalActor.actor ? HakukohteetHaussaQuery(hakuOid, organisaatio, hakukohdekoodi))
        .mapTo[List[KoutaInternalHakukohdeLite]]

    hakukohteidenTiedot.flatMap(hks => {
      val oids = hks.map(_.oid).distinct
      val hakukohdekoodiByHakukohdeOid =
        hks.map(hk => hk.oid -> hk.hakukohde.map(h => h.koodiUri)).toMap
      val hakemukset: Future[List[AtaruHakemusToinenAste]] =
        ataruhakemuksetToinenAste(
          AtaruSearchParams(
            hakijaOids = None,
            hakukohdeOids = Some(oids),
            hakuOid = Some(hakuOid),
            organizationOid = organisaatio,
            modifiedAfter = None
          ),
          hakukohdekoodiByHakukohdeOid
        )
      //Filtteröidään pois sellaiset hakutoiveet, jotka eivät kuulu haettuihin hakukohteisiin
      hakemukset.map(haks =>
        haks.map(hakemus =>
          hakemus.copy(hakutoiveet =
            hakemus.hakutoiveet.map(hts =>
              hts.filter(ht => oids.contains(ht.koulutusId.getOrElse("")))
            )
          )
        )
      )
    })
  }

  def suoritusoikeudenTaiAiemmanTutkinnonVuosi(
    hakuOid: String,
    hakukohdeOid: Option[String]
  ): Future[Seq[HakijaHakemus]] = {
    for {
      hakuappHakemukset <- hakuappRestClient.postObject[ListFullSearchDto, List[FullHakemus]](
        "haku-app.listfull"
      )(acceptedResponseCode = 200, ListFullSearchDto.suoritusvuosi(hakukohdeOid, hakuOid))
      ataruHakemukset <- ataruhakemukset(
        AtaruSearchParams(
          hakijaOids = None,
          hakukohdeOids = hakukohdeOid.map(List(_)),
          hakuOid = Some(hakuOid),
          organizationOid = None,
          modifiedAfter = None
        )
      )
    } yield hakuappHakemukset ++ ataruHakemukset
  }

  def personOidsForHaku(hakuOid: String, organisaatio: Option[String]): Future[Set[String]] = {
    for {
      hakuappPersonOids <- hakuappRestClient.postObject[Set[String], Set[String]](
        "haku-app.personoidsbyapplicationsystem",
        organisaatio.orNull
      )(200, Set(hakuOid))
      ataruHakemukset <- ataruhakemukset(
        AtaruSearchParams(
          hakijaOids = None,
          hakukohdeOids = None,
          hakuOid = Some(hakuOid),
          organizationOid = organisaatio,
          modifiedAfter = None
        )
      )
    } yield hakuappPersonOids ++ ataruHakemukset.flatMap(_.personOid)
  }

  def personOidsForHakukohde(
    hakukohdeOid: String,
    organisaatio: Option[String]
  ): Future[Set[String]] = {
    for {
      hakuappPersonOids <- hakuappRestClient.postObject[Set[String], Set[String]](
        "haku-app.personoidsbyapplicationoption",
        organisaatio.orNull
      )(200, Set(hakukohdeOid))
      ataruHakemukset <- ataruhakemukset(
        AtaruSearchParams(
          hakijaOids = None,
          hakukohdeOids = Some(List(hakukohdeOid)),
          hakuOid = None,
          organizationOid = organisaatio,
          modifiedAfter = None
        )
      )
    } yield hakuappPersonOids ++ ataruHakemukset.flatMap(_.personOid)
  }

  def hetuAndPersonOidForHaku(hakuOid: String): Future[Seq[HetuPersonOid]] = {
    for {
      ataruHakemukset <- ataruhakemukset(
        AtaruSearchParams(
          hakijaOids = None,
          hakukohdeOids = None,
          hakuOid = Some(hakuOid),
          organizationOid = None,
          modifiedAfter = None
        ),
        skipResolvingTarjoaja = true
      )
    } yield ataruHakemukset.collect({
      case h: AtaruHakemus if h.henkilo.hetu.isDefined =>
        HetuPersonOid(hetu = h.henkilo.hetu.get, personOid = h.henkilo.oidHenkilo)
    })
  }

  def hetuAndPersonOidForPersonOid(personOid: String): Future[Seq[HakemusHakuHetuPersonOid]] = {
    for {
      hakuappHakemukset <- hakuappRestClient
        .postObject[Set[String], Map[String, Seq[FullHakemus]]](
          "haku-app.bypersonoid"
        )(acceptedResponseCode = 200, Set(personOid))
      ataruHakemukset <- ataruhakemukset(
        AtaruSearchParams(
          hakijaOids = Some(List(personOid)),
          hakukohdeOids = None,
          hakuOid = None,
          organizationOid = None,
          modifiedAfter = None
        ),
        skipResolvingTarjoaja = true
      )
    } yield (hakuappHakemukset.values.flatten.toList ++ ataruHakemukset).collect({
      case h: FullHakemus if h.stateValid && h.hetu.isDefined && h.personOid.isDefined =>
        HakemusHakuHetuPersonOid(
          hakemus = h.oid,
          haku = h.applicationSystemId,
          hetu = h.hetu.get,
          personOid = h.personOid.get
        )
      case h: AtaruHakemus if h.stateValid && h.henkilo.hetu.isDefined =>
        HakemusHakuHetuPersonOid(
          hakemus = h.oid,
          haku = h.applicationSystemId,
          hetu = h.henkilo.hetu.get,
          personOid = h.henkilo.oidHenkilo
        )
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

  def processModifiedHakemukset(
    modifiedAfter: Date = new Date(Platform.currentTime - TimeUnit.DAYS.toMillis(2)),
    refreshFrequency: FiniteDuration = 1.minute
  )(implicit scheduler: Scheduler): Unit = {
    scheduler.scheduleOnce(refreshFrequency)({
      val lastChecked = new Date()
      val formattedDate = new SimpleDateFormat("yyyyMMddHHmm").format(modifiedAfter)
      logger.info(
        "processModifiedHakemukset : Fetching modified hakemukses from haku-app and ataru, " +
          s"modified since $formattedDate"
      )
      val allApplications: Future[List[HakijaHakemus]] = for {
        hakuappApplications: Seq[FullHakemus] <- fetchHakemuksetChunked(
          params = SearchParams(updatedAfter = formattedDate)
        )
        ataruApplications: List[HakijaHakemus] <- ataruhakemukset(
          AtaruSearchParams(None, None, None, None, Some(formattedDate))
        )
      } yield hakuappApplications.toList ::: ataruApplications
      allApplications
        .flatMap(aa => {
          logger.info(
            s"processModifiedHakemukset : found ${aa.size} hakemukses. Fetching aliases."
          )
          fetchPersonAliases(aa)
        })
        .onComplete {
          case Success((hakemukset, personOidsWithAliases)) =>
            logger.info(
              s"processModifiedHakemukset : Successfully fetched aliases for " +
                s"${hakemukset.size} hakemukses with formattedDate $formattedDate."
            )
            Try(triggerHakemukset(hakemukset, personOidsWithAliases)) match {
              case Failure(e) =>
                logger.error(e, "processModifiedHakemukset : Exception in trigger!")
              case _ =>
            }
            processModifiedHakemukset(lastChecked, refreshFrequency)
          case Failure(t) =>
            logger
              .error(t, "processModifiedHakemukset : Fetching modified hakemukset failed, retrying")
            processModifiedHakemukset(modifiedAfter, refreshFrequency)
        }
    })
  }

  private def fetchPersonAliases(
    hs: Seq[HakijaHakemus]
  ): Future[(Seq[HakijaHakemus], PersonOidsWithAliases)] = {
    oppijaNumeroRekisteri.enrichWithAliases(hs.flatMap(_.personOid).toSet).map((hs, _))
  }

  private def triggerHakemukset(
    hakemukset: Seq[HakijaHakemus],
    personOidsWithAliases: PersonOidsWithAliases
  ): Unit =
    hakemukset
      .collect({ case h: HakijaHakemus => h })
      .foreach(hakemus => triggers.foreach(trigger => trigger.f(hakemus, personOidsWithAliases)))

  private def fetchHakemuksetChunked(params: SearchParams): Future[Seq[FullHakemus]] = {
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
    hakuappRestClient
      .readObject[List[FullHakemus]]("haku-app.listfull", params.copy(start = page * pageSize))(
        acceptedResponseCode = 200,
        maxRetries = 2
      )
      .flatMap(hakemukset =>
        if (hakemukset.length < pageSize) {
          Future.successful(hakemukset)
        } else {
          fetchHakemukset(page + 1, params).map(hakemukset ++ _)
        }
      )
  }
}

class HakemusServiceMock extends IHakemusService {
  override def hakemuksetForPerson(personOid: String) = Future.successful(Seq[FullHakemus]())
  override def hakemuksetForPersons(personOids: Set[String]) = Future.successful(Seq[FullHakemus]())
  override def personOidstoMasterOids(personOids: Set[String]) =
    Future.successful(personOids.map(kv => (kv, kv)).toMap)

  override def hakemuksetForHakukohde(hakukohdeOid: String, organisaatio: Option[String]) =
    Future.successful(Seq[FullHakemus]())

  override def hakemuksetForHakukohdes(hakukohdeOids: Set[String], organisaatio: Option[String]) =
    Future.successful(Seq[FullHakemus]())

  override def personOidsForHaku(hakuOid: String, organisaatio: Option[String]) =
    Future.successful(Set[String]())

  override def personOidsForHakukohde(hakukohdeOid: String, organisaatio: Option[String]) =
    Future.successful(Set[String]())

  override def hakemuksetForHaku(hakuOid: String, organisaatio: Option[String]) =
    Future.successful(Seq[FullHakemus]())

  override def hakemuksetForToisenAsteenAtaruHaku(
    hakuOid: String,
    organisaatio: Option[String],
    hakukohdekoodi: Option[String]
  ): Future[Seq[AtaruHakemusToinenAste]] = {
    Future.successful(Seq[AtaruHakemusToinenAste]())
  }

  override def suoritusoikeudenTaiAiemmanTutkinnonVuosi(
    hakuOid: String,
    hakukohdeOid: Option[String]
  ): Future[Seq[FullHakemus]] = Future.successful(Seq[FullHakemus]())

  override def hakemuksetForPersonsInHaku(personOids: Set[String], hakuOid: String) =
    Future.successful(Seq[FullHakemus]())

  override def addTrigger(trigger: Trigger): Unit = ()

  override def reprocessHaunHakemukset(hakuOid: String): Unit = ()

  override def hetuAndPersonOidForHaku(hakuOid: String) = Future.successful(Seq[HetuPersonOid]())

  override def hetuAndPersonOidForPersonOid(
    personOid: String
  ): Future[Seq[HakemusHakuHetuPersonOid]] = Future.successful(Seq[HakemusHakuHetuPersonOid]())
}
