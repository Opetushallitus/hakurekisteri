package fi.vm.sade.hakurekisteri.integration.hakemus

import akka.actor.{ActorRef, ActorSystem}
import akka.event.Logging
import akka.pattern.ask
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.hakija._
import fi.vm.sade.hakurekisteri.hakija.representation.UrheilijanLisakysymykset
import fi.vm.sade.hakurekisteri.integration.haku.{GetHaku, Haku}
import fi.vm.sade.hakurekisteri.integration.henkilo.{
  IOppijaNumeroRekisteri,
  Kieli,
  PersonOidsWithAliases
}
import fi.vm.sade.hakurekisteri.integration.koodisto.{
  GetRinnasteinenKoodiArvoQuery,
  KoodistoActorRef
}
import fi.vm.sade.hakurekisteri.integration.kooste.IKoosteService
import fi.vm.sade.hakurekisteri.integration.koski.{
  IKoskiService,
  KoskiHenkiloContainer,
  OppivelvollisuusTieto
}
import fi.vm.sade.hakurekisteri.integration.organisaatio.Organisaatio
import fi.vm.sade.hakurekisteri.integration.valintalaskentatulos.{
  IValintalaskentaTulosService,
  LaskennanTulosHakemukselle,
  LaskennanTulosValinnanvaihe
}
import fi.vm.sade.hakurekisteri.integration.{ExecutorUtil, VirkailijaRestClient}
import fi.vm.sade.hakurekisteri.opiskelija.{Opiskelija, OpiskelijaHenkilotQuery}
import fi.vm.sade.hakurekisteri.rest.support.{Kausi, Resource}
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.suoritus.{
  Komoto,
  Suoritus,
  SuoritusQuery,
  SuoritusQueryWithPersonAliases,
  VirallinenSuoritus,
  yksilollistaminen
}
import fi.vm.sade.hakurekisteri.{Config, Oids}
import org.joda.time.{DateTime, LocalDate, MonthDay}
import org.slf4j.LoggerFactory._

import java.io
import java.text.SimpleDateFormat
import java.util.concurrent.TimeUnit
import scala.collection.immutable.Iterable
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try

trait Hakupalvelu {
  def getHakijatByQuery(q: HakijaQuery): Future[Seq[Hakija]]
  def getHakijat(q: HakijaQuery, haku: Haku): Future[Seq[Hakija]]
  def getToisenAsteenAtaruHakijat(q: HakijaQuery, haku: Haku): Future[Seq[Hakija]]
  def getHakukohdeOids(hakukohderyhma: String, haku: String): Future[Seq[String]]
}

case class ThemeQuestion(
  `type`: String,
  messageText: String,
  applicationOptionOids: Seq[String],
  options: Option[Map[String, String]],
  isHaunLisakysymys: Boolean = false
)

case class HakukohdeResult(hakukohteenNimiUri: String)

case class HakukohdeResultContainer(result: HakukohdeResult)

case class HakukohdeSearchResult(oid: String)

case class HakukohdeSearchResultTarjoaja(tulokset: Seq[HakukohdeSearchResult])

case class HakukohdeSearchResultList(tulokset: Seq[HakukohdeSearchResultTarjoaja])

case class HakukohdeSearchResultContainer(result: HakukohdeSearchResultList)

class AkkaHakupalvelu(
  virkailijaClient: VirkailijaRestClient,
  hakemusService: IHakemusService,
  koosteService: IKoosteService,
  hakuActor: ActorRef,
  koodisto: KoodistoActorRef,
  config: Config,
  koskiService: IKoskiService,
  opiskeluActor: ActorRef,
  onr: IOppijaNumeroRekisteri,
  valintalaskenta: IValintalaskentaTulosService
)(implicit val system: ActorSystem)
    extends Hakupalvelu {

  private val logger = Logging.getLogger(system, this)

  implicit val ec: ExecutionContext = ExecutorUtil.createExecutor(
    config.integrations.asyncOperationThreadPoolSize,
    getClass.getSimpleName
  )
  private implicit val defaultTimeout: Timeout = 120.seconds
  private val oppivelvollisuusTiedotTimeOut: Duration = Duration(20L, TimeUnit.SECONDS)
  private val acceptedResponseCode: Int = 200
  private val maxRetries: Int = 2

  private def restRequest[A <: AnyRef](uri: String, args: AnyRef*)(implicit
    mf: Manifest[A]
  ): Future[A] =
    virkailijaClient.readObject[A](uri, args: _*)(acceptedResponseCode, maxRetries)

  private def getLisakysymyksetForAtaruHaku(
    kohdejoukkoUri: Option[String]
  ): Map[String, ThemeQuestion] = {
    AkkaHakupalvelu.hardcodedLisakysymykset(kohdejoukkoUri)
  }

  private def getLisakysymyksetForHaku(
    hakuOid: String,
    kohdejoukkoUri: Option[String]
  ): Future[Map[String, ThemeQuestion]] = {
    restRequest[Map[String, ThemeQuestion]]("haku-app.themequestions", hakuOid).map(kysymykset =>
      kysymykset ++ AkkaHakupalvelu.hardcodedLisakysymykset(kohdejoukkoUri)
    )
  }

  private def getUusinOpiskelijatietoForOpiskelijas(
    oids: Seq[String]
  ): Future[Map[String, Opiskelija]] = {
    implicit val dateTimeOrdering: Ordering[DateTime] = _ compareTo _

    for {
      aliakset: PersonOidsWithAliases <- onr.enrichWithAliases(oids.toSet)
      opiskelijat: Seq[Opiskelija] <- (opiskeluActor ? OpiskelijaHenkilotQuery(aliakset))
        .mapTo[Seq[Opiskelija]]
    } yield {
      oids
        .map(oid =>
          oid -> {
            val henkilonAliakset = aliakset.aliasesByPersonOids.getOrElse(oid, Set(oid))
            val opiskelutiedot = opiskelijat
              .filter(o => henkilonAliakset.contains(o.henkiloOid))
              .sortBy(o => o.alkuPaiva)
              .reverse
            opiskelutiedot.headOption
          }
        )
        .collect { case e if e._2.isDefined => e._1 -> e._2.get }
        .toMap
    }
  }

  private def getOppivelvollisuustiedot(
    hakijaHakemukset: Seq[HakijaHakemus],
    version: Int
  ): Seq[OppivelvollisuusTieto] = {
    logger.info(s"Haetaan oppivelvollisuustiedot ${hakijaHakemukset.size} hakemukselle")
    // This data is used only for api version 5 and greater
    if (version < 5 || hakijaHakemukset.isEmpty) {
      return Seq[OppivelvollisuusTieto]()
    }
    try {
      val hakijaOids = hakijaHakemukset.map(_.personOid).filter(_.isDefined).map(_.get).distinct
      Await.result(
        koskiService.fetchOppivelvollisuusTietos(hakijaOids),
        oppivelvollisuusTiedotTimeOut
      )
    } catch {
      case e: Throwable =>
        logger.error(e, "getOppivelvollisuustiedot : virhe haettaessa oppivelvollisuustietoja")
        throw e
    }

  }

  override def getHakukohdeOids(hakukohderyhma: String, haku: String): Future[Seq[String]] = {
    restRequest[HakukohdeSearchResultContainer](
      "tarjonta-service.hakukohde.search",
      Map("hakuOid" -> haku, "organisaatioRyhmaOid" -> hakukohderyhma)
    )
      .map(_.result.tulokset.flatMap(tarjoaja => tarjoaja.tulokset.map(hakukohde => hakukohde.oid)))
  }

  private def hakukohdeOids(
    organisaatio: Option[String],
    hakuOid: Option[String],
    hakukohdekoodi: Option[String]
  ): Future[Seq[String]] = {
    val hakukohteenNimiUriHakuehto = hakukohdekoodi.map(koodi => "hakukohteenNimiUri" -> koodi)
    val q = (organisaatio.map("organisationOid" -> _) ++ hakuOid.map(
      "hakuOid" -> _
    ) ++ hakukohteenNimiUriHakuehto).toMap
    restRequest[HakukohdeSearchResultContainer]("tarjonta-service.hakukohde.search", q)
      .map(_.result.tulokset.flatMap(tarjoaja => tarjoaja.tulokset.map(hakukohde => hakukohde.oid)))
  }

  private def maatjavaltiot2To1AtaruToinenAste(
    hakemukset: Seq[AtaruHakemusToinenAste]
  ): Future[Map[String, String]] = {
    Future
      .sequence(
        hakemukset
          .collect({ case h: AtaruHakemusToinenAste => h })
          .flatMap(h => h.asuinmaa :: h.henkilo.kansalaisuus.map(_.kansalaisuusKoodi))
          .distinct
          .map({
            case "246" => Future.successful("246" -> "FIN")
            case "736" => Future.successful("736" -> "XXX")
            case "810" => Future.successful("810" -> "XXX")
            case "891" => Future.successful("891" -> "XXX")
            case "990" => Future.successful("990" -> "XXX")
            case koodi =>
              (koodisto.actor ? GetRinnasteinenKoodiArvoQuery(
                "maatjavaltiot2",
                koodi,
                "maatjavaltiot1"
              )).mapTo[String].map(koodi -> _)
          })
      )
      .map(_.toMap)
  }

  private def maatjavaltiot2To1(hakemukset: Seq[HakijaHakemus]): Future[Map[String, String]] = {
    Future
      .sequence(
        hakemukset
          .collect({ case h: AtaruHakemus => h })
          .flatMap(h => h.asuinmaa :: h.henkilo.kansalaisuus.map(_.kansalaisuusKoodi))
          .distinct
          .map({
            case "246" => Future.successful("246" -> "FIN")
            case "736" => Future.successful("736" -> "XXX")
            case "810" => Future.successful("810" -> "XXX")
            case "891" => Future.successful("891" -> "XXX")
            case "990" => Future.successful("990" -> "XXX")
            case koodi =>
              (koodisto.actor ? GetRinnasteinenKoodiArvoQuery(
                "maatjavaltiot2",
                koodi,
                "maatjavaltiot1"
              )).mapTo[String].map(koodi -> _)
          })
      )
      .map(_.toMap)
  }

  def getHakemustenHakutoiveidenLaskennanTulokset(
    hakemukset: Seq[AtaruHakemusToinenAste],
    q: HakijaQuery
  ): Future[Map[String, Seq[LaskennanTulosValinnanvaihe]]] = {
    q.version match {
      case v if v >= 6 =>
        val hakukohdeOids: Set[String] = hakemukset
          .flatMap(hak =>
            hak.hakutoiveet
              .getOrElse(Seq.empty)
              .map(toive => toive.koulutusId)
          )
          .filter(_.isDefined)
          .flatten
          .toSet
        valintalaskenta.getHakukohteidenValinnanvaiheet(hakukohdeOids)
      case _ => Future.successful(Map.empty)
    }
  }

  override def getToisenAsteenAtaruHakijat(q: HakijaQuery, haku: Haku): Future[Seq[Hakija]] = {
    q match {
      case HakijaQuery(Some(hakuOid), organisaatio, hakukohdekoodi, hakukohdeOid, _, _, _) =>
        for {
          hakemukset <- hakemusService
            .hakemuksetForToisenAsteenAtaruHaku(hakuOid, organisaatio, hakukohdekoodi, hakukohdeOid)
          harkinnanvaraisuudet: Seq[HakemuksenHarkinnanvaraisuus] <- koosteService
            .getHarkinnanvaraisuudet(hakemukset)
          hakukohteidenTulokset <- getHakemustenHakutoiveidenLaskennanTulokset(hakemukset, q)
          hakijaSuorituksetMap <- koosteService.getSuorituksetForAtaruhakemukset(
            hakuOid,
            hakemukset
          )
          maakoodit <- maatjavaltiot2To1AtaruToinenAste(hakemukset)
          oppivelvollisuusTiedot = getOppivelvollisuustiedot(hakemukset, q.version)
          personOids = hakemukset.map(h => h.personOid.get)
          hakijoidenOpiskelijatiedot: Map[String, Opiskelija] <-
            getUusinOpiskelijatietoForOpiskelijas(personOids)
        } yield hakemukset
          .map { hakemus =>
            val koosteData: Map[String, String] =
              hakijaSuorituksetMap.getOrElse(hakemus.personOid.get, Map.empty)
            AkkaHakupalvelu.getToisenAsteenAtaruHakija(
              hakemus,
              haku,
              koosteData,
              maakoodit,
              oppivelvollisuusTiedot,
              hakijoidenOpiskelijatiedot.get(hakemus.personOid.get),
              harkinnanvaraisuudet.filter(h => h.hakemusOid.equals(hakemus.oid)).head,
              hakukohteidenTulokset,
              q
            )
          }
      case _ =>
        logger.warning(s"Strange HakijaQuery received: $q, doing nothing.")
        Future.successful(Seq.empty)
    }
  }

  override def getHakijat(q: HakijaQuery, haku: Haku): Future[Seq[Hakija]] = {
    def hakuAndLisakysymykset(hakuOid: String): Future[(Haku, Map[String, ThemeQuestion])] = {
      val hakuF = (hakuActor ? GetHaku(hakuOid)).mapTo[Haku]
      for {
        haku <- hakuF
        lisakysymykset <- getLisakysymyksetForHaku(hakuOid, haku.kohdejoukkoUri)
      } yield (haku, lisakysymykset)
    }

    q match {
      case HakijaQuery(Some(hakuOid), organisaatio, None, _, _, _, _) =>
        for {
          (haku, lisakysymykset) <- hakuAndLisakysymykset(hakuOid)
          hakemukset <- hakemusService
            .hakemuksetForHaku(hakuOid, organisaatio)
            .map(_.filter(_.stateValid))
          hakijaSuorituksetMap <- koosteService.getSuoritukset(hakuOid, hakemukset)
          maakoodit <- maatjavaltiot2To1(hakemukset)
          oppivelvollisuusTiedot = getOppivelvollisuustiedot(hakemukset, q.version)
        } yield hakemukset
          .map { hakemus =>
            val koosteData: Option[Map[String, String]] =
              hakijaSuorituksetMap.get(hakemus.personOid.get)
            AkkaHakupalvelu.getHakija(
              hakemus,
              haku,
              lisakysymykset,
              None,
              koosteData,
              maakoodit,
              oppivelvollisuusTiedot
            )
          }
      case HakijaQuery(hakuOid, organisaatio, hakukohdekoodi, _, _, _, _) =>
        for {
          hakukohdeOids <- hakukohdeOids(organisaatio, hakuOid, hakukohdekoodi)
          hakukohteittain <- Future.sequence(
            hakukohdeOids.map(hakemusService.hakemuksetForHakukohde(_, organisaatio))
          )
          hauittain = hakukohdeOids
            .zip(hakukohteittain)
            .groupBy(_._2.headOption.map(_.applicationSystemId))
          hakijat <- Future.sequence(for {
            (hakuOid, hakukohteet) <- hauittain
            if hakuOid.isDefined // when would it not be defined?
            hakuJaLisakysymykset = hakuAndLisakysymykset(hakuOid.get)
            (hakukohdeOid, hakemukset) <- hakukohteet
            maakooditF = maatjavaltiot2To1(hakemukset)
            suorituksetByOppija = koosteService.getSuoritukset(
              hakuOid.get,
              hakemukset.filter(_.stateValid)
            )
            hakemus <- hakemukset if hakemus.stateValid
          } yield for {
            hakijaSuorituksetMap <- suorituksetByOppija
            (haku, lisakysymykset) <- hakuJaLisakysymykset
            maakoodit <- maakooditF
            oppivelvollisuusTiedot = getOppivelvollisuustiedot(hakemukset, q.version)
          } yield {
            val koosteData: Option[Map[String, String]] =
              hakijaSuorituksetMap.get(hakemus.personOid.get)
            AkkaHakupalvelu.getHakija(
              hakemus,
              haku,
              lisakysymykset,
              hakukohdekoodi.map(_ => hakukohdeOid),
              koosteData,
              maakoodit,
              oppivelvollisuusTiedot
            )
          })
        } yield hakijat.toSeq
    }
  }

  override def getHakijatByQuery(q: HakijaQuery): Future[Seq[Hakija]] = {
    if (q.haku.isEmpty) {
      throw new RuntimeException("Haku is not defined!")
    }
    (hakuActor ? GetHaku(q.haku.get))
      .mapTo[Haku]
      .flatMap {
        case haku: Haku
            if haku.hakulomakeAtaruId.isDefined && haku.toisenAsteenHaku && q.version >= 5 && !haku.isJatkuvaHaku =>
          logger.info(
            s"Getting ataruhakijat for toinen aste, query: ${q.copy(user = None)}"
          )
          getToisenAsteenAtaruHakijat(q, haku)
        case haku: Haku
            if haku.hakulomakeAtaruId.isDefined && haku.isJatkuvaHaku && q.version >= 6 =>
          logger.info(s"Getting ataruhakijat for jatkuva haku, query: ${q.copy(user = None)}")
          getToisenAsteenAtaruHakijat(q, haku)
        case haku: Haku =>
          logger.info(s"Getting hakijat for legacy haku, query: ${q.copy(user = None)}")
          getHakijat(q, haku)
      }
  }
}

object AkkaHakupalvelu {
  val DEFAULT_POHJA_KOULUTUS: String = "1"

  def hardcodedLisakysymykset(kohdejoukkoUri: Option[String]): Map[String, ThemeQuestion] = {
    val isErkkaHaku = kohdejoukkoUri.exists(_.startsWith("haunkohdejoukko_20"))
    hardcodedLisakysymyksetForAll ++ (if (isErkkaHaku) hardcodedLisakysymyksetForErkkaHaku
                                      else Map.empty)
  }

  val hardcodedLisakysymyksetForAll: Map[String, ThemeQuestion] = Map(
    "TYOKOKEMUSKUUKAUDET" -> ThemeQuestion(
      isHaunLisakysymys = true,
      `type` = "ThemeTextQuestion",
      messageText = "Työkokemus kuukausina",
      applicationOptionOids = Nil,
      options = None
    ),
    "lupaTulosEmail" -> ThemeQuestion(
      isHaunLisakysymys = true,
      `type` = "ThemeRadioButtonQuestion",
      messageText = "Oppilaitos saa toimittaa päätöksen opiskelijavalinnasta sähköpostiini",
      applicationOptionOids = Nil,
      options = Some(Map("true" -> "Kyllä", "false" -> "Ei"))
    ),
    "lupaSms" -> ThemeQuestion(
      isHaunLisakysymys = true,
      `type` = "ThemeRadioButtonQuestion",
      messageText =
        "Minulle saa lähettää tietoa opiskelijavalinnan etenemisestä ja tuloksista myös tekstiviestillä.",
      applicationOptionOids = Nil,
      options = Some(Map("true" -> "Kyllä", "false" -> "Ei"))
    )
  )

  val hardcodedLisakysymyksetForErkkaHaku: Map[String, ThemeQuestion] = Map(
    "muutsuoritukset" -> ThemeQuestion(
      isHaunLisakysymys = true,
      `type` = "ThemeTextQuestion",
      messageText = "Minkä muun koulutuksen/opintoja olet suorittanut?",
      applicationOptionOids = Nil,
      options = None
    ),
    "hojks" -> ThemeQuestion(
      isHaunLisakysymys = true,
      `type` = "ThemeRadioButtonQuestion",
      messageText =
        "Onko sinulle laadittu peruskoulussa tai muita opintoja suorittaessasi HOJKS (Henkilökohtainen opetuksen järjestämistä koskeva " +
          "suunnitelma)?",
      applicationOptionOids = Nil,
      options = Some(Map("true" -> "Kyllä", "false" -> "Ei"))
    ),
    "koulutuskokeilu" -> ThemeQuestion(
      isHaunLisakysymys = true,
      `type` = "ThemeRadioButtonQuestion",
      messageText = "Oletko ollut koulutuskokeilussa?",
      applicationOptionOids = Nil,
      options = Some(Map("true" -> "Kyllä", "false" -> "Ei"))
    ),
    "miksi_ammatilliseen" -> ThemeQuestion(
      isHaunLisakysymys = true,
      `type` = "ThemeTextQuestion",
      messageText = "Miksi haet erityisoppilaitokseen?",
      applicationOptionOids = Nil,
      options = None
    )
  )

  def getVuosi(vastaukset: Koulutustausta)(
    pohjakoulutus: String
  )(koosteData: Option[Map[String, String]]): Option[String] = pohjakoulutus match {
    case "9" => vastaukset.lukioPaattotodistusVuosi
    case "7" => Some((LocalDate.now.getYear + 1).toString)
    case _ =>
      val vuosiKoosteDatasta = koosteData.flatMap(_.get("PK_PAATTOTODISTUSVUOSI"))
      if (vuosiKoosteDatasta.isDefined) vuosiKoosteDatasta else vastaukset.PK_PAATTOTODISTUSVUOSI
  }

  private def getLisapisteKoulutus(koosteData: Map[String, String]): Option[String] = {
    val lisapisteKoulutukset = List(
      "LISAKOULUTUS_KYMPPI",
      "LISAKOULUTUS_VAMMAISTEN",
      "LISAKOULUTUS_TALOUS",
      "LISAKOULUTUS_AMMATTISTARTTI",
      "LISAKOULUTUS_KANSANOPISTO",
      "LISAKOULUTUS_MAAHANMUUTTO",
      "LISAKOULUTUS_MAAHANMUUTTO_LUKIO",
      "LISAKOULUTUS_VALMA",
      "LISAKOULUTUS_OPISTOVUOSI"
    )
    lisapisteKoulutukset.find(lpk => koosteData.getOrElse(lpk, "false").equals("true"))
  }

  def kaydytLisapisteKoulutukset(tausta: Koulutustausta): scala.Iterable[String] = {
    def checkKoulutus(lisakoulutus: Option[String]): Boolean = {
      Try(lisakoulutus.getOrElse("false").toBoolean).getOrElse(false)
    }
    Map(
      "LISAKOULUTUS_KYMPPI" -> tausta.LISAKOULUTUS_KYMPPI,
      "LISAKOULUTUS_VAMMAISTEN" -> tausta.LISAKOULUTUS_VAMMAISTEN,
      "LISAKOULUTUS_TALOUS" -> tausta.LISAKOULUTUS_TALOUS,
      "LISAKOULUTUS_AMMATTISTARTTI" -> tausta.LISAKOULUTUS_AMMATTISTARTTI,
      "LISAKOULUTUS_KANSANOPISTO" -> tausta.LISAKOULUTUS_KANSANOPISTO,
      "LISAKOULUTUS_MAAHANMUUTTO" -> tausta.LISAKOULUTUS_MAAHANMUUTTO,
      "LISAKOULUTUS_MAAHANMUUTTO_LUKIO" -> tausta.LISAKOULUTUS_MAAHANMUUTTO_LUKIO,
      "LISAKOULUTUS_VALMA" -> tausta.LISAKOULUTUS_VALMA,
      "LISAKOULUTUS_OPISTOVUOSI" -> tausta.LISAKOULUTUS_OPISTOVUOSI
    ).mapValues(checkKoulutus).filter { case (_, done) => done }.keys
  }

  def attachmentRequestToLiite(har: HakemusAttachmentRequest): Liite = {
    val name = (har.applicationAttachment.name, har.applicationAttachment.header) match {
      case (Some(name), _)   => name.translations.fi
      case (_, Some(header)) => header.translations.fi
      case (None, None)      => ""
    }
    var prefAoId = (har.preferenceAoId) match {
      case Some(a) => a
      case None    => ""
    }
    var prefAoGroupId = (har.preferenceAoGroupId) match {
      case Some(a) => a
      case None    => ""
    }
    Liite(
      prefAoId,
      prefAoGroupId,
      har.receptionStatus,
      har.processingStatus,
      name,
      har.applicationAttachment.address.recipient
    )
  }

  def getLisakysymykset(
    hakemus: FullHakemus,
    lisakysymykset: Map[String, ThemeQuestion],
    hakukohdeOid: Option[String],
    kohdejoukkoUri: Option[String]
  ): Seq[Lisakysymys] = {

    case class CompositeId(questionId: String, answerId: Option[String])

    def extractCompositeIds(avain: String): CompositeId = {
      val parts = avain.split("-")
      if (parts.size == 2) {
        CompositeId(parts(0), Some(parts(1)))
      } else {
        CompositeId(parts(0), None)
      }
    }

    def mergeAnswers(questionId: String, questions: Iterable[Lisakysymys]): Lisakysymys = {
      val q = questions.head
      Lisakysymys(
        kysymysid = q.kysymysid,
        hakukohdeOids = q.hakukohdeOids,
        kysymystyyppi = q.kysymystyyppi,
        kysymysteksti = q.kysymysteksti,
        vastaukset = questions.flatMap(_.vastaukset).toSeq
      )
    }

    def extractLisakysymysFromAnswer(avain: String, arvo: String): Lisakysymys = {
      val compositeIds = extractCompositeIds(avain)
      val kysymysId = compositeIds.questionId
      val kysymys: ThemeQuestion =
        lisakysymykset.getOrElse(kysymysId, (hardcodedLisakysymykset(kohdejoukkoUri))(kysymysId))
      Lisakysymys(
        kysymysid = kysymysId,
        hakukohdeOids = kysymys.applicationOptionOids,
        kysymystyyppi = kysymys.`type`,
        kysymysteksti = kysymys.messageText,
        vastaukset = getAnswersByType(kysymys, arvo, compositeIds.answerId)
      )
    }

    def getAnswersByType(
      tq: ThemeQuestion,
      vastaus: String,
      vastausId: Option[String]
    ): Seq[LisakysymysVastaus] = {
      tq.`type` match {
        case "ThemeRadioButtonQuestion" =>
          Seq(
            LisakysymysVastaus(
              vastausid = Some(vastaus),
              vastausteksti = tq.options.get.apply(if (vastaus.isEmpty) "false" else vastaus)
            )
          )
        case "ThemeCheckBoxQuestion" =>
          if (vastaus != "true") {
            Seq()
          } else {
            Seq(
              LisakysymysVastaus(
                vastausid = vastausId,
                vastausteksti = tq.options.get(vastausId.get)
              )
            )
          }
        case _ =>
          Seq(
            LisakysymysVastaus(
              vastausid = None,
              vastausteksti = vastaus
            )
          )
      }
    }

    def thatAreLisakysymysInHakukohde(kysymysId: String): Boolean = {
      lisakysymykset.keys.exists { (key: String) =>
        kysymysId.contains(key) && {
          val noHakukohdeSpecified = hakukohdeOid.isEmpty
          val isDefaultKysymys = lisakysymykset(key).isHaunLisakysymys
          val isKysymysForSpecifiedHakukohde = !noHakukohdeSpecified && lisakysymykset(
            key
          ).applicationOptionOids.contains(hakukohdeOid.get)
          val kysymysIncludedForCurrentHakukohde =
            isDefaultKysymys || isKysymysForSpecifiedHakukohde

          noHakukohdeSpecified || kysymysIncludedForCurrentHakukohde
        }
      }
    }

    val answers: HakemusAnswers = hakemus.answers.getOrElse(HakemusAnswers())
    val flatAnswers = answers.hakutoiveet.getOrElse(Map()) ++ answers.osaaminen.getOrElse(
      Map()
    ) ++ answers.lisatiedot.getOrElse(Map())

    val missingQuestionAnswers: Map[String, String] =
      hardcodedLisakysymykset(kohdejoukkoUri)
        .filterNot(q => {
          val id = q._1
          flatAnswers.contains(id)
        })
        .map(q => (q._1, ""))

    /*
    The missing question answers are added here forcefully since the excel sheets and what not need to have them in all cases
     */
    val filteredAnswers: Map[String, String] =
      flatAnswers.filterKeys(thatAreLisakysymysInHakukohde) ++ missingQuestionAnswers
    val finalanswers: Seq[Lisakysymys] = filteredAnswers
      .map { case (avain, arvo) => extractLisakysymysFromAnswer(avain, arvo) }
      .groupBy(_.kysymysid)
      .map { case (questionId, answerList) => mergeAnswers(questionId, answerList) }
      .toSeq

    finalanswers
  }

  private def getOppivelvollisuusTieto(
    field: (OppivelvollisuusTieto) => Option[String],
    hakijaOid: Option[String],
    oppivelvollisuusTiedot: Seq[OppivelvollisuusTieto]
  ): Option[String] = {
    oppivelvollisuusTiedot.find(_.oid == hakijaOid.getOrElse("")).map(field(_)).getOrElse(None)
  }

  def getToisenAsteenAtaruHakija(
    h: HakijaHakemus,
    haku: Haku,
    hakijanKoosteData: Map[String, String],
    maakoodit: Map[String, String],
    oppivelvollisuusTiedot: Seq[OppivelvollisuusTieto],
    viimeisinOpiskelutieto: Option[Opiskelija],
    harkinnanvaraisuus: HakemuksenHarkinnanvaraisuus,
    laskennanTulosHakutoiveille: Map[String, Seq[LaskennanTulosValinnanvaihe]],
    query: HakijaQuery
  ): Hakija = h match {
    case hakemus: AtaruHakemusToinenAste =>
      val hakutoiveidenKeskiarvot: Map[String, String] =
        if (query.version >= 6)
          h.hakutoiveet
            .getOrElse(List.empty)
            .map(ht =>
              ht.koulutusId.get -> laskennanTulosHakutoiveille
                .getOrElse(ht.koulutusId.get, Seq.empty)
                .map(_.keskiarvoHakemukselle(h.oid))
                .find(_.isDefined)
                .flatten
                .getOrElse("")
            )
            .toMap
        else Map.empty

      val lisapistekoulutus: Option[String] = getLisapisteKoulutus(hakijanKoosteData)

      val kesa: MonthDay = new MonthDay(6, 4)

      val myontaja: String = viimeisinOpiskelutieto.map(o => o.oppilaitosOid).getOrElse("")

      val pohjakoulutusKooste = hakijanKoosteData.get("POHJAKOULUTUS")
      val pohjakoulutusHakemus = {
        if (hakemus.pohjakoulutus.nonEmpty) Some(hakemus.pohjakoulutus) else None
        //if jatkuva haku and 7 use hakemus.pohjakouluus
      }
      if (pohjakoulutusKooste.isEmpty) {
        println(
          s"Käytetään hakemukselle ${hakemus.oid} hakemuksen pohjakoulutusta $pohjakoulutusHakemus, koska koostepalvelusta ei löytynyt"
        )
      }

      /**
        * Valintalaskentakoostepalveluun ei ole haluttu tehdä tukea jatkuvan haun pohjakoulutusarvoille, jos sieltä
        * tulee 7 (keskeytynyt), niin käytetään hakemuksen arvoa
        */
      val pohjakoulutus =
        if (
          pohjakoulutusKooste.isDefined && !(pohjakoulutusKooste.get == "7" && haku.isJatkuvaHaku)
        )
          pohjakoulutusKooste
        else pohjakoulutusHakemus

      val todistusVuosiPK = hakijanKoosteData.get("PK_PAATTOTODISTUSVUOSI")
      val todistusVuosiLK = hakijanKoosteData.get("LK_PAATTOTODISTUSVUOSI")

      val todistusVuosi = (todistusVuosiPK, todistusVuosiLK) match {
        case (Some(pk), _)    => todistusVuosiPK
        case (None, Some(lk)) => todistusVuosiLK
        case _                => hakemus.tutkintoVuosi.map(v => v.toString)
      }

      val valmistuminen = todistusVuosi
        .flatMap(vuosi => Try(kesa.toLocalDate(vuosi.toInt)).toOption)
        .getOrElse(Suoritus.realValmistuminenNotKnownLocalDate)

      val opetuskieli: Option[String] =
        hakijanKoosteData.get("perusopetuksen_kieli")
      val suorittaja: String = hakemus.personOid.getOrElse("")
      val hakutoiveet: Seq[Hakutoive] =
        hakemus.hakutoiveet
          .map(toiveet => convertToiveet(toiveet, haku, hakutoiveidenKeskiarvot))
          .getOrElse(Seq.empty)
      val hakutoiveetHarkinnanvaraisuuksilla = hakutoiveet
        .map(ht =>
          ht.copy(harkinnanvaraisuusperuste =
            harkinnanvaraisuus.hakutoiveet
              .find(hht => hht.hakukohdeOid.equals(ht.hakukohde.oid))
              .map(hht => convertHarkinnanvaraisuudenSyy(hht.harkinnanvaraisuudenSyy))
          )
        )
      Hakija(
        Henkilo(
          lahiosoite = hakemus.lahiosoite,
          postinumero = hakemus.postinumero,
          maa = maakoodit.getOrElse(hakemus.asuinmaa, "FIN"),
          postitoimipaikka = hakemus.postitoimipaikka.getOrElse(""),
          matkapuhelin = hakemus.matkapuhelin,
          puhelin = "",
          sahkoposti = hakemus.email,
          kotikunta = hakemus.kotikunta.getOrElse("999"),
          sukunimi = hakemus.henkilo.sukunimi.getOrElse(""),
          etunimet = hakemus.henkilo.etunimet.getOrElse(""),
          kutsumanimi = hakemus.henkilo.kutsumanimi.getOrElse(""),
          turvakielto = hakemus.henkilo.turvakielto.toString,
          oppijanumero = hakemus.henkilo.oidHenkilo,
          kansalaisuus = Some(
            hakemus.henkilo.kansalaisuus.headOption
              .flatMap(k => maakoodit.get(k.kansalaisuusKoodi))
              .getOrElse("FIN")
          ),
          kaksoiskansalaisuus = None,
          kansalaisuudet =
            Some(hakemus.henkilo.kansalaisuus.flatMap(k => maakoodit.get(k.kansalaisuusKoodi))),
          asiointiKieli = hakemus.asiointiKieli.toUpperCase,
          aidinkieli = hakemus.henkilo.aidinkieli.map(_.kieliKoodi.toUpperCase).getOrElse("99"),
          opetuskieli = opetuskieli.getOrElse(hakemus.tutkintoKieli.getOrElse("")),
          eiSuomalaistaHetua = hakemus.henkilo.hetu.isEmpty,
          sukupuoli = hakemus.henkilo.sukupuoli.getOrElse(""),
          hetu = hakemus.henkilo.hetu.getOrElse(""),
          syntymaaika = hakemus.henkilo.syntymaaika
            .map(s =>
              new SimpleDateFormat("dd.MM.yyyy").format(new SimpleDateFormat("yyyy-MM-dd").parse(s))
            )
            .getOrElse(""),
          markkinointilupa = Some(hakemus.koulutusmarkkinointilupa),
          kiinnostunutoppisopimuksesta = hakemus.kiinnostunutOppisopimusKoulutuksesta,
          huoltajannimi = hakemus.huoltajat
            .filter(h => h.nimi.isDefined)
            .map(h => h.nimi.get)
            .filter(_.nonEmpty)
            .mkString(", "),
          huoltajanpuhelinnumero = hakemus.huoltajat
            .filter(h => h.matkapuhelin.isDefined)
            .map(h => h.matkapuhelin.get)
            .filter(_.nonEmpty)
            .mkString(", "),
          huoltajansahkoposti = hakemus.huoltajat
            .filter(h => h.email.isDefined)
            .map(h => h.email.get)
            .filter(_.nonEmpty)
            .mkString(", "),
          oppivelvollisuusVoimassaAsti = getOppivelvollisuusTieto(
            _.oppivelvollisuusVoimassaAsti,
            h.personOid,
            oppivelvollisuusTiedot
          ),
          oikeusMaksuttomaanKoulutukseenVoimassaAsti = getOppivelvollisuusTieto(
            _.oikeusMaksuttomaanKoulutukseenVoimassaAsti,
            h.personOid,
            oppivelvollisuusTiedot
          ),
          lisakysymykset = Seq.empty,
          liitteet = Seq.empty,
          muukoulutus = None
        ),
        getSuoritus(
          pohjakoulutus,
          myontaja,
          valmistuminen,
          suorittaja,
          opetuskieli.getOrElse("FI"),
          hakemus.personOid
        ).toSeq,
        viimeisinOpiskelutieto.map(tieto => Seq(tieto)).getOrElse(Seq.empty),
        Hakemus(
          hakutoiveet = hakutoiveetHarkinnanvaraisuuksilla,
          hakemusnumero = hakemus.oid,
          julkaisulupa = hakemus.valintatuloksenJulkaisulupa,
          hakuOid = haku.oid,
          lisapistekoulutus = lisapistekoulutus,
          liitteet = Seq.empty,
          osaaminen = Osaaminen(
            yleinen_kielitutkinto_fi = None,
            valtionhallinnon_kielitutkinto_fi = None,
            yleinen_kielitutkinto_sv = None,
            valtionhallinnon_kielitutkinto_sv = None,
            yleinen_kielitutkinto_en = None,
            valtionhallinnon_kielitutkinto_en = None,
            yleinen_kielitutkinto_se = None,
            valtionhallinnon_kielitutkinto_se = None
          )
        ),
        ataruHakemus = Some(hakemus)
      )
    case _ => throw new RuntimeException("Wrong type of hakemus!")
  }

  def getHakija(
    h: HakijaHakemus,
    haku: Haku,
    lisakysymykset: Map[String, ThemeQuestion],
    hakukohdeOid: Option[String],
    koosteData: Option[Map[String, String]],
    maakoodit: Map[String, String],
    oppivelvollisuusTiedot: Seq[OppivelvollisuusTieto]
  ): Hakija = h match {
    case hakemus: FullHakemus =>
      def getOsaaminenOsaalue(key: String): Option[String] = {
        hakemus.answers.flatMap(_.osaaminen match {
          case Some(a) => a.get(key)
          case None    => None
        })
      }
      val kesa: MonthDay = new MonthDay(6, 4)
      val koulutustausta: Option[Koulutustausta] = hakemus.koulutustausta
      val lahtokoulu: Option[String] = hakemus.lahtokoulu
      val myontaja: String = lahtokoulu.getOrElse("")
      val pohjakoulutus: Option[String] = for (k <- koosteData; p <- k.get("POHJAKOULUTUS")) yield p
      val todistusVuosi: Option[String] =
        for (p: String <- pohjakoulutus; k <- koulutustausta; v <- getVuosi(k)(p)(koosteData))
          yield v
      val valmistuminen: LocalDate = todistusVuosi
        .flatMap(vuosi => Try(kesa.toLocalDate(vuosi.toInt)).toOption)
        .getOrElse(Suoritus.realValmistuminenNotKnownLocalDate)
      val kieli: String = hakemus.aidinkieli
      val opetuskieli: Option[String] = koosteData.flatMap(_.get("perusopetuksen_kieli"))
      val suorittaja: String = hakemus.personOid.getOrElse("")
      val yleinen_kielitutkinto_fi = getOsaaminenOsaalue("yleinen_kielitutkinto_fi")
      val valtionhallinnon_kielitutkinto_fi = getOsaaminenOsaalue(
        "valtionhallinnon_kielitutkinto_fi"
      )
      val yleinen_kielitutkinto_sv = getOsaaminenOsaalue("yleinen_kielitutkinto_sv")
      val valtionhallinnon_kielitutkinto_sv = getOsaaminenOsaalue(
        "valtionhallinnon_kielitutkinto_sv"
      )
      val yleinen_kielitutkinto_en = getOsaaminenOsaalue("yleinen_kielitutkinto_en")
      val valtionhallinnon_kielitutkinto_en = getOsaaminenOsaalue(
        "valtionhallinnon_kielitutkinto_en"
      )
      val yleinen_kielitutkinto_se = getOsaaminenOsaalue("yleinen_kielitutkinto_se")
      val valtionhallinnon_kielitutkinto_se = getOsaaminenOsaalue(
        "valtionhallinnon_kielitutkinto_se"
      )

      val osaaminen = Osaaminen(
        yleinen_kielitutkinto_fi,
        valtionhallinnon_kielitutkinto_fi,
        yleinen_kielitutkinto_sv,
        valtionhallinnon_kielitutkinto_sv,
        yleinen_kielitutkinto_en,
        valtionhallinnon_kielitutkinto_en,
        yleinen_kielitutkinto_se,
        valtionhallinnon_kielitutkinto_se
      )

      val lisapistekoulutus =
        for (
          tausta <- koulutustausta;
          lisatausta <- kaydytLisapisteKoulutukset(tausta).headOption
        ) yield lisatausta
      val henkilotiedot: Option[HakemusHenkilotiedot] = hakemus.henkilotiedot

      def getHenkiloTietoOrElse(f: (HakemusHenkilotiedot) => Option[String], orElse: String) =
        (for (h <- henkilotiedot; osoite <- f(h)) yield osoite).getOrElse(orElse)

      def getHenkiloTietoOrBlank(f: (HakemusHenkilotiedot) => Option[String]): String =
        getHenkiloTietoOrElse(f, "")

      def parseKansalaisuusList(
        a: (HakemusHenkilotiedot) => Option[String],
        b: (HakemusHenkilotiedot) => Option[String]
      ): Option[List[String]] = {
        val primary = getHenkiloTietoOrElse(a, "")
        val secondary = getHenkiloTietoOrElse(b, "")
        if (secondary.isEmpty) Some(List(primary)) else Some(List(primary, secondary))
      }

      Hakija(
        Henkilo(
          lahiosoite = getHenkiloTietoOrElse(_.lahiosoite, getHenkiloTietoOrBlank(_.osoiteUlkomaa)),
          postinumero = getHenkiloTietoOrElse(_.Postinumero, "00000"),
          postitoimipaikka =
            getHenkiloTietoOrElse(_.Postitoimipaikka, getHenkiloTietoOrBlank(_.kaupunkiUlkomaa)),
          maa = getHenkiloTietoOrElse(_.asuinmaa, "FIN"),
          matkapuhelin = getHenkiloTietoOrBlank(_.matkapuhelinnumero1),
          puhelin = getHenkiloTietoOrBlank(_.matkapuhelinnumero2),
          sahkoposti = getHenkiloTietoOrBlank(_.Sähköposti),
          kotikunta = getHenkiloTietoOrBlank(_.kotikunta),
          sukunimi = getHenkiloTietoOrBlank(_.Sukunimi),
          etunimet = getHenkiloTietoOrBlank(_.Etunimet),
          kutsumanimi = getHenkiloTietoOrBlank(_.Kutsumanimi),
          turvakielto = getHenkiloTietoOrBlank(_.Turvakielto),
          oppijanumero = hakemus.personOid.getOrElse(""),
          kansalaisuus = Some(getHenkiloTietoOrElse(_.kansalaisuus, "FIN")),
          kaksoiskansalaisuus = Some(getHenkiloTietoOrBlank(_.kaksoiskansalaisuus)),
          kansalaisuudet = parseKansalaisuusList(_.kansalaisuus, _.kaksoiskansalaisuus),
          asiointiKieli = kieli,
          aidinkieli = kieli,
          opetuskieli = opetuskieli.getOrElse(""),
          eiSuomalaistaHetua =
            getHenkiloTietoOrElse(_.onkoSinullaSuomalainenHetu, "false").toBoolean,
          sukupuoli = getHenkiloTietoOrBlank(_.sukupuoli),
          hetu = getHenkiloTietoOrBlank(_.Henkilotunnus),
          syntymaaika = getHenkiloTietoOrBlank(_.syntymaaika),
          markkinointilupa = Some(hakemus.markkinointilupa),
          kiinnostunutoppisopimuksesta = Some(
            hakemus.answers
              .flatMap(
                _.lisatiedot.flatMap(_.get("kiinnostunutoppisopimuksesta").filter(_.trim.nonEmpty))
              )
              .getOrElse("false")
              .toBoolean
          ),
          huoltajannimi = getHenkiloTietoOrBlank(_.huoltajannimi),
          huoltajanpuhelinnumero = getHenkiloTietoOrBlank(_.huoltajanpuhelinnumero),
          huoltajansahkoposti = getHenkiloTietoOrBlank(_.huoltajansahkoposti),
          oppivelvollisuusVoimassaAsti = getOppivelvollisuusTieto(
            _.oppivelvollisuusVoimassaAsti,
            h.personOid,
            oppivelvollisuusTiedot
          ),
          oikeusMaksuttomaanKoulutukseenVoimassaAsti = getOppivelvollisuusTieto(
            _.oikeusMaksuttomaanKoulutukseenVoimassaAsti,
            h.personOid,
            oppivelvollisuusTiedot
          ),
          lisakysymykset =
            getLisakysymykset(hakemus, lisakysymykset, hakukohdeOid, haku.kohdejoukkoUri),
          liitteet = hakemus.attachmentRequests.map(a => attachmentRequestToLiite(a)),
          muukoulutus = hakemus.koulutustausta.flatMap(_.muukoulutus)
        ),
        getSuoritus(
          pohjakoulutus,
          myontaja,
          valmistuminen,
          suorittaja,
          kieli,
          hakemus.personOid
        ).toSeq,
        lahtokoulu match {
          case Some(oid) =>
            Seq(
              Opiskelija(
                oppilaitosOid = lahtokoulu.get,
                henkiloOid = hakemus.personOid.getOrElse(""),
                luokkataso =
                  hakemus.answers.flatMap(_.koulutustausta.flatMap(_.luokkataso)).getOrElse(""),
                luokka =
                  hakemus.answers.flatMap(_.koulutustausta.flatMap(_.lahtoluokka)).getOrElse(""),
                alkuPaiva = DateTime.now.minus(org.joda.time.Duration.standardDays(1)),
                loppuPaiva = None,
                source = Oids.ophOrganisaatioOid
              )
            )
          case _ => Seq()
        },
        Hakemus(
          hakemus.hakutoiveet
            .map(toiveet => convertToiveet(toiveet, haku, Map.empty))
            .getOrElse(Seq.empty),
          hakemus.oid,
          hakemus.julkaisulupa,
          hakemus.applicationSystemId,
          lisapistekoulutus,
          Seq(),
          osaaminen
        )
      )
    case hakemus: AtaruHakemus =>
      Hakija(
        Henkilo(
          lahiosoite = hakemus.lahiosoite,
          postinumero = hakemus.postinumero,
          maa = maakoodit.getOrElse(hakemus.asuinmaa, "FIN"),
          postitoimipaikka = hakemus.postitoimipaikka.getOrElse(""),
          matkapuhelin = hakemus.matkapuhelin,
          puhelin = "",
          sahkoposti = hakemus.email,
          kotikunta = hakemus.kotikunta.getOrElse("999"),
          sukunimi = hakemus.henkilo.sukunimi.getOrElse(""),
          etunimet = hakemus.henkilo.etunimet.getOrElse(""),
          kutsumanimi = hakemus.henkilo.kutsumanimi.getOrElse(""),
          turvakielto = hakemus.henkilo.turvakielto.toString,
          oppijanumero = hakemus.henkilo.oidHenkilo,
          kansalaisuus = Some(
            hakemus.henkilo.kansalaisuus.headOption
              .flatMap(k => maakoodit.get(k.kansalaisuusKoodi))
              .getOrElse("FIN")
          ),
          kaksoiskansalaisuus = None,
          kansalaisuudet =
            Some(hakemus.henkilo.kansalaisuus.flatMap(k => maakoodit.get(k.kansalaisuusKoodi))),
          asiointiKieli = hakemus.asiointiKieli.toUpperCase,
          aidinkieli = hakemus.henkilo.aidinkieli.map(_.kieliKoodi.toUpperCase).getOrElse("99"),
          opetuskieli = "",
          eiSuomalaistaHetua = hakemus.henkilo.hetu.isEmpty,
          sukupuoli = hakemus.henkilo.sukupuoli.getOrElse(""),
          hetu = hakemus.henkilo.hetu.getOrElse(""),
          syntymaaika = hakemus.henkilo.syntymaaika
            .map(s =>
              new SimpleDateFormat("dd.MM.yyyy").format(new SimpleDateFormat("yyyy-MM-dd").parse(s))
            )
            .getOrElse(""),
          markkinointilupa = Some(hakemus.markkinointilupa),
          kiinnostunutoppisopimuksesta = None,
          huoltajannimi = "",
          huoltajanpuhelinnumero = "",
          huoltajansahkoposti = "",
          oppivelvollisuusVoimassaAsti = getOppivelvollisuusTieto(
            _.oppivelvollisuusVoimassaAsti,
            h.personOid,
            oppivelvollisuusTiedot
          ),
          oikeusMaksuttomaanKoulutukseenVoimassaAsti = getOppivelvollisuusTieto(
            _.oikeusMaksuttomaanKoulutukseenVoimassaAsti,
            h.personOid,
            oppivelvollisuusTiedot
          ),
          lisakysymykset = Seq.empty,
          liitteet = Seq.empty,
          muukoulutus = None
        ),
        Seq.empty,
        Seq.empty,
        Hakemus(
          hakutoiveet = hakemus.hakutoiveet
            .map(toiveet => convertToiveet(toiveet, haku, Map.empty))
            .getOrElse(Seq.empty),
          hakemusnumero = hakemus.oid,
          julkaisulupa = hakemus.julkaisulupa,
          hakuOid = hakemus.applicationSystemId,
          lisapistekoulutus = None,
          liitteet = Seq.empty,
          osaaminen = Osaaminen(
            yleinen_kielitutkinto_fi = None,
            valtionhallinnon_kielitutkinto_fi = None,
            yleinen_kielitutkinto_sv = None,
            valtionhallinnon_kielitutkinto_sv = None,
            yleinen_kielitutkinto_en = None,
            valtionhallinnon_kielitutkinto_en = None,
            yleinen_kielitutkinto_se = None,
            valtionhallinnon_kielitutkinto_se = None
          )
        )
      )
    case _ =>
      ??? //Atarusta haetut toisen asteen hakemukset käsitellään toisaalla, kts AtaruHakemusToinenAste
  }

  def getJatkuvaHaku2AsteYksilollistaminen(koodiarvo: Option[String]): yksilollistaminen.Value = {
    koodiarvo match {
      case Some("POY")  => yksilollistaminen.Osittain
      case Some("PKYO") => yksilollistaminen.Kokonaan
      case Some("PYOT") => yksilollistaminen.Alueittain
      case _            => yksilollistaminen.Ei
    }
  }

  def is2AsteenJatkuvaHakuPohjaKoulutus(koodiarvo: Option[String]): Boolean = {
    koodiarvo match {
      case Some("APT") | Some("ATEAT") | Some("KK") | Some("YO") | Some("EIPT") | Some("PO") | Some(
            "POY"
          ) | Some("PKYO") | Some("PYOT") | Some("TUVA10") | Some("UK") =>
        true
      case _ => false
    }
  }

  def getSuoritus(
    pohjakoulutus: Option[String],
    myontaja: String,
    valmistuminen: LocalDate,
    suorittaja: String,
    kieli: String,
    hakija: Option[String]
  ): Option[Suoritus] = {
    Seq(pohjakoulutus).collectFirst {
      case Some("0") =>
        VirallinenSuoritus(
          "ulkomainen",
          myontaja,
          if (LocalDate.now.isBefore(valmistuminen)) "KESKEN" else "VALMIS",
          valmistuminen,
          suorittaja,
          yksilollistaminen.Ei,
          kieli,
          vahv = false,
          lahde = hakija.getOrElse(Oids.ophOrganisaatioOid)
        )
      case Some("1") =>
        VirallinenSuoritus(
          "peruskoulu",
          myontaja,
          if (LocalDate.now.isBefore(valmistuminen)) "KESKEN" else "VALMIS",
          valmistuminen,
          suorittaja,
          yksilollistaminen.Ei,
          kieli,
          vahv = false,
          lahde = hakija.getOrElse(Oids.ophOrganisaatioOid)
        )
      case Some("2") =>
        VirallinenSuoritus(
          "peruskoulu",
          myontaja,
          if (LocalDate.now.isBefore(valmistuminen)) "KESKEN" else "VALMIS",
          valmistuminen,
          suorittaja,
          yksilollistaminen.Osittain,
          kieli,
          vahv = false,
          lahde = hakija.getOrElse(Oids.ophOrganisaatioOid)
        )
      case Some("3") =>
        VirallinenSuoritus(
          "peruskoulu",
          myontaja,
          if (LocalDate.now.isBefore(valmistuminen)) "KESKEN" else "VALMIS",
          valmistuminen,
          suorittaja,
          yksilollistaminen.Alueittain,
          kieli,
          vahv = false,
          lahde = hakija.getOrElse(Oids.ophOrganisaatioOid)
        )
      case Some("6") =>
        VirallinenSuoritus(
          "peruskoulu",
          myontaja,
          if (LocalDate.now.isBefore(valmistuminen)) "KESKEN" else "VALMIS",
          valmistuminen,
          suorittaja,
          yksilollistaminen.Kokonaan,
          kieli,
          vahv = false,
          lahde = hakija.getOrElse(Oids.ophOrganisaatioOid)
        )
      case Some("9") =>
        VirallinenSuoritus(
          "lukio",
          myontaja,
          if (LocalDate.now.isBefore(valmistuminen)) "KESKEN" else "VALMIS",
          valmistuminen,
          suorittaja,
          yksilollistaminen.Ei,
          kieli,
          vahv = false,
          lahde = hakija.getOrElse(Oids.ophOrganisaatioOid)
        )
      case jatkuvahaku2aste: Some[String] if is2AsteenJatkuvaHakuPohjaKoulutus(jatkuvahaku2aste) =>
        VirallinenSuoritus(
          jatkuvahaku2aste.get,
          myontaja,
          if (jatkuvahaku2aste.get.equals("EIPT")) "KESKEN" else "VALMIS",
          valmistuminen,
          suorittaja,
          getJatkuvaHaku2AsteYksilollistaminen(jatkuvahaku2aste),
          kieli,
          vahv = false,
          lahde = hakija.getOrElse(Oids.ophOrganisaatioOid)
        )
    }
  }

  def findEnsimmainenAmmatillinenKielinenHakukohde(
    toiveet: List[HakutoiveDTO],
    kieli: String
  ): Int = {
    toiveet
      .find(t =>
        t.koulutusIdLang.getOrElse("") == kieli && t.koulutusIdVocational.getOrElse("") == "true"
      )
      .map(_.preferenceNumber)
      .getOrElse(0)
  }

  def convertHarkinnanvaraisuudenSyy(syy: String): String = {
    syy match {
      case "EI_HARKINNANVARAINEN" | "EI_HARKINNANVARAINEN_HAKUKOHDE"                 => ""
      case "ATARU_OPPIMISVAIKEUDET"                                                  => "1"
      case "ATARU_SOSIAALISET_SYYT"                                                  => "2"
      case "ATARU_KOULUTODISTUSTEN_VERTAILUVAIKEUDET" | "ATARU_ULKOMAILLA_OPISKELTU" => "3"
      case "SURE_EI_PAATTOTODISTUSTA" | "ATARU_EI_PAATTOTODISTUSTA"                  => "4"
      case "ATARU_RIITTAMATON_TUTKINTOKIELEN_TAITO"                                  => "5"
      case "SURE_YKS_MAT_AI" | "ATARU_YKS_MAT_AI"                                    => "6"
      case _ =>
        System.out.println(s"No mapping found for $syy")
        "999"
    }
  }

  def convertToiveet(
    toiveet: List[HakutoiveDTO],
    haku: Haku,
    keskiarvot: Map[String, String]
  ): Seq[Hakutoive] = {
    val ensimmainenSuomenkielinenHakukohde: Int =
      findEnsimmainenAmmatillinenKielinenHakukohde(toiveet, "FI")
    val ensimmainenRuotsinkielinenHakukohde: Int =
      findEnsimmainenAmmatillinenKielinenHakukohde(toiveet, "SV")
    val ensimmainenEnglanninkielinenHakukohde: Int =
      findEnsimmainenAmmatillinenKielinenHakukohde(toiveet, "EN")
    val ensimmainenSaamenkielinenHakukohde: Int =
      findEnsimmainenAmmatillinenKielinenHakukohde(toiveet, "SE")

    val opetusPisteet: Seq[(Short, String)] = toiveet.collect {
      case toive if toive.organizationOid.isDefined =>
        (toive.preferenceNumber.toShort, toive.organizationOid.get)
    }

    opetusPisteet.sortBy(_._1).map { case (jno, tarjoajaoid) =>
      val toive = toiveet.find(_.preferenceNumber == jno).get
      val koulutukset = Set(
        Komoto(
          "",
          "",
          tarjoajaoid,
          haku.koulutuksenAlkamisvuosi.map(_.toString),
          haku.koulutuksenAlkamiskausi.map(Kausi.fromKoodiUri)
        )
      )
      val hakukohdekoodi = toive.koulutusIdAoIdentifier.getOrElse("hakukohdekoodi")
      val kaksoistutkinto =
        toive.kaksoistutkinnonLisakysymys.map(s => Try(s.toBoolean).getOrElse(false))
      val urheilijanammatillinenkoulutus =
        toive.urheilijanAmmatillisenLisakysymys.map(s => Try(s.toBoolean).getOrElse(false))
      val harkinnanvaraisuusperuste: Option[String] = toive.discretionaryFollowUp.flatMap {
        case "oppimisvaikudet"              => Some("1")
        case "sosiaalisetsyyt"              => Some("2")
        case "todistustenvertailuvaikeudet" => Some("3")
        case "todistustenpuuttuminen"       => Some("4")
        case "riittamatonkielitaito"        => Some("5")
        case s => //logger.error(s"invalid discretionary-follow-up value $s");
          None
      }
      val aiempiperuminen = toive.soraOikeudenMenetys.map(s => Try(s.toBoolean).getOrElse(false))
      val terveys = toive.soraTerveys.map(s => Try(s.toBoolean).getOrElse(false))
      val hakukohdeOid = toive.koulutusId.getOrElse("")
      val koulutuksenKieli: Option[String] = (
        jno == ensimmainenSuomenkielinenHakukohde,
        jno == ensimmainenRuotsinkielinenHakukohde,
        jno == ensimmainenEnglanninkielinenHakukohde,
        jno == ensimmainenSaamenkielinenHakukohde
      ) match {
        case (true, false, false, false) => Some("FI")
        case (false, true, false, false) => Some("SV")
        case (false, false, true, false) => Some("EN")
        case (false, false, false, true) => Some("SE")
        case _                           => None
      }
      Hakutoive(
        jno,
        Hakukohde(koulutukset, hakukohdekoodi, hakukohdeOid),
        kaksoistutkinto,
        urheilijanammatillinenkoulutus,
        harkinnanvaraisuusperuste,
        aiempiperuminen,
        terveys,
        None,
        toive.organizationParentOids,
        koulutuksenKieli,
        None,
        None,
        None,
        keskiarvo = keskiarvot.get(hakukohdeOid),
        urheilijanLisakysymykset = toive.urheilijanLisakysymykset
      )
    }
  }
}

case class ListHakemus(oid: String)

case class HakemusHaku(totalCount: Long, results: Seq[ListHakemus])

case class HakemusHenkilotiedot(
  Henkilotunnus: Option[String] = None,
  aidinkieli: Option[String] = None,
  lahiosoite: Option[String] = None,
  Postinumero: Option[String] = None,
  Postitoimipaikka: Option[String] = None,
  osoiteUlkomaa: Option[String] = None,
  postinumeroUlkomaa: Option[String] = None,
  kaupunkiUlkomaa: Option[String] = None,
  asuinmaa: Option[String] = None,
  matkapuhelinnumero1: Option[String] = None,
  matkapuhelinnumero2: Option[String] = None,
  Sähköposti: Option[String] = None,
  kotikunta: Option[String] = None,
  Sukunimi: Option[String] = None,
  Etunimet: Option[String] = None,
  Kutsumanimi: Option[String] = None,
  Turvakielto: Option[String] = None,
  kansalaisuus: Option[String] = None,
  kaksoiskansalaisuus: Option[String] = None,
  onkoSinullaSuomalainenHetu: Option[String] = None,
  sukupuoli: Option[String] = None,
  syntymaaika: Option[String] = None,
  koulusivistyskieli: Option[String] = None,
  turvakielto: Option[String] = None,
  huoltajannimi: Option[String] = None,
  huoltajanpuhelinnumero: Option[String] = None,
  huoltajansahkoposti: Option[String] = None,
  oppivelvollisuusVoimassaAsti: Option[String] = None,
  oikeusMaksuttomaanKoulutukseenVoimassaAsti: Option[String] = None
)

case class Koulutustausta(
  lahtokoulu: Option[String],
  POHJAKOULUTUS: Option[String],
  lukioPaattotodistusVuosi: Option[String],
  PK_PAATTOTODISTUSVUOSI: Option[String],
  KYMPPI_PAATTOTODISTUSVUOSI: Option[String],
  LISAKOULUTUS_KYMPPI: Option[String],
  LISAKOULUTUS_VAMMAISTEN: Option[String],
  LISAKOULUTUS_TALOUS: Option[String],
  LISAKOULUTUS_AMMATTISTARTTI: Option[String],
  LISAKOULUTUS_KANSANOPISTO: Option[String],
  LISAKOULUTUS_MAAHANMUUTTO: Option[String],
  LISAKOULUTUS_MAAHANMUUTTO_LUKIO: Option[String],
  LISAKOULUTUS_VALMA: Option[String],
  LISAKOULUTUS_OPISTOVUOSI: Option[String],
  luokkataso: Option[String],
  lahtoluokka: Option[String],
  perusopetuksen_kieli: Option[String],
  lukion_kieli: Option[String],
  pohjakoulutus_yo: Option[String],
  pohjakoulutus_yo_vuosi: Option[String],
  pohjakoulutus_am: Option[String],
  pohjakoulutus_am_vuosi: Option[String],
  pohjakoulutus_amt: Option[String],
  pohjakoulutus_amt_vuosi: Option[String],
  pohjakoulutus_kk: Option[String],
  pohjakoulutus_kk_pvm: Option[String],
  pohjakoulutus_ulk: Option[String],
  pohjakoulutus_ulk_vuosi: Option[String],
  pohjakoulutus_avoin: Option[String],
  pohjakoulutus_muu: Option[String],
  pohjakoulutus_muu_vuosi: Option[String],
  aiempitutkinto_tutkinto: Option[String],
  aiempitutkinto_korkeakoulu: Option[String],
  aiempitutkinto_vuosi: Option[String],
  suoritusoikeus_tai_aiempi_tutkinto: Option[String],
  suoritusoikeus_tai_aiempi_tutkinto_vuosi: Option[String],
  muukoulutus: Option[String]
)

object Koulutustausta {
  def apply(): Koulutustausta = Koulutustausta(
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None
  )
}

@SerialVersionUID(1)
case class HakemusAnswers(
  henkilotiedot: Option[HakemusHenkilotiedot] = None,
  koulutustausta: Option[Koulutustausta] = None,
  lisatiedot: Option[Map[String, String]] = None,
  hakutoiveet: Option[Map[String, String]] = None,
  osaaminen: Option[Map[String, String]] = None
)

@SerialVersionUID(1)
case class HakemusAttachmentRequest(
  id: String,
  preferenceAoId: Option[String],
  preferenceAoGroupId: Option[String],
  processingStatus: String,
  receptionStatus: String,
  applicationAttachment: ApplicationAttachment
)

case class ApplicationAttachment(name: Option[Name], header: Option[Header], address: Address)

case class Name(translations: Translations)

case class Header(translations: Translations)

case class Address(recipient: String, streetAddress: String, postalCode: String, postOffice: String)

case class Translations(fi: String, sv: String, en: String)

@SerialVersionUID(1)
case class PreferenceEligibility(
  aoId: String,
  status: String,
  source: Option[String],
  maksuvelvollisuus: Option[String]
)

sealed trait HakijaHakemus {
  def personOid: Option[String]
  def hetu: Option[String]
  def oid: String
  def applicationSystemId: String
  def stateValid: Boolean
  def hakutoiveet: Option[List[HakutoiveDTO]]
}

@SerialVersionUID(1)
case class HakutoiveDTO(
  preferenceNumber: Int,
  koulutusId: Option[String],
  koulutusIdAoIdentifier: Option[String],
  koulutusIdLang: Option[String],
  koulutusIdVocational: Option[String],
  organizationOid: Option[String],
  organizationParentOids: Set[String],
  kaksoistutkinnonLisakysymys: Option[String],
  soraOikeudenMenetys: Option[String],
  soraTerveys: Option[String],
  urheilijanAmmatillisenLisakysymys: Option[String],
  discretionaryFollowUp: Option[String],
  urheilijanLisakysymykset: Option[UrheilijanLisakysymykset] = None
)

@SerialVersionUID(1)
case class FullHakemus(
  oid: String,
  personOid: Option[String],
  applicationSystemId: String,
  answers: Option[HakemusAnswers],
  state: Option[String],
  preferenceEligibilities: Seq[PreferenceEligibility],
  attachmentRequests: Seq[HakemusAttachmentRequest] = Seq(),
  received: Option[Long],
  updated: Option[Long]
) extends Resource[String, FullHakemus]
    with Identified[String]
    with HakijaHakemus
    with Serializable {

  // Resource stuff
  override def identify(identity: String): FullHakemus with Identified[String] = this
  override val core: AnyRef = oid
  def newId: String = oid
  val source: String = Oids.ophOrganisaatioOid

  // Identified stuff
  val id: String = oid

  // Hakemus stuff
  val stateValid: Boolean = state.exists(s => Seq("ACTIVE", "INCOMPLETE").contains(s))
  val henkilotiedot: Option[HakemusHenkilotiedot] = answers.flatMap(_.henkilotiedot)
  val hetu: Option[String] =
    henkilotiedot.flatMap(henkilo => henkilo.Henkilotunnus.map(henkiloHetu => henkiloHetu))
  val hakutoiveet: Option[List[HakutoiveDTO]] = {
    val preferencesGroupedByOrder: Option[Map[Int, Map[String, String]]] = answers
      .flatMap(_.hakutoiveet)
      .map(
        _.filter(p =>
          "^preference[0-9]".r.findFirstIn(p._1).isDefined
        ) // Only take actual preference data into account
          .groupBy(m => { // Group preferences into map by preference index
            val preferencePrefix: String = m._1.split(s"-|_").head
            val preferenceNumber: Int = "\\d+$".r
              .findFirstIn(preferencePrefix)
              .map(_.toInt)
              .getOrElse(
                throw new IllegalArgumentException(
                  s"Could not parse hakukutoive preference number from $preferencePrefix"
                )
              )
            preferenceNumber
          })
      )

    def parseParentOids(s: String): Set[String] = {
      val parentOids = s.split(",").filterNot(_.isEmpty).toSet
      if (!parentOids.forall(Organisaatio.isOrganisaatioOid)) {
        throw new IllegalArgumentException(s"Could not parse parent oids $s")
      }
      parentOids
    }

    preferencesGroupedByOrder.map(_.map { case (index, preference) =>
      HakutoiveDTO(
        index,
        preference.get(s"preference${index}-Koulutus-id"),
        preference.get(s"preference${index}-Koulutus-id-aoIdentifier"),
        preference.get(s"preference${index}-Koulutus-id-lang"),
        preference.get(s"preference${index}-Koulutus-id-vocational"),
        preference.get(s"preference${index}-Opetuspiste-id"),
        preference
          .get(s"preference${index}-Opetuspiste-id-parents")
          .map(parseParentOids)
          .getOrElse(Set()),
        preference.get(s"preference${index}_kaksoistutkinnon_lisakysymys"),
        preference.get(s"preference${index}_sora_oikeudenMenetys"),
        preference.get(s"preference${index}_sora_terveys"),
        preference.get(s"preference${index}_urheilijan_ammatillisen_koulutuksen_lisakysymys"),
        preference.get(s"preference${index}-discretionary-follow-up")
      )
    }.toList)
  }

  val koulutustausta: Option[Koulutustausta] = answers.flatMap(_.koulutustausta)
  val lahtokoulu: Option[String] = koulutustausta.flatMap(_.lahtokoulu)
  val aidinkieli: String = henkilotiedot.flatMap(h => h.aidinkieli).getOrElse("FI")
  val julkaisulupa: Boolean =
    answers.flatMap(_.lisatiedot).flatMap(_.get("lupaJulkaisu")).getOrElse("false").toBoolean
  val markkinointilupa: Boolean =
    answers.flatMap(_.lisatiedot).flatMap(_.get("lupaMarkkinointi")).getOrElse("false").toBoolean
}

case class AtaruResponseHenkilot(
  applications: List[AtaruHakemuksenHenkilotiedot],
  offset: Option[String]
)

case class AtaruResponse(applications: List[AtaruHakemusDto], offset: Option[String])
case class AtaruResponseToinenAste(
  applications: List[AtaruHakemusToinenAsteDto],
  offset: Option[String]
)

case class AtaruHakemusDto(
  oid: String,
  personOid: String,
  applicationSystemId: String,
  createdTime: String,
  hakemusFirstSubmittedTime: String,
  kieli: String,
  hakukohteet: List[String],
  email: String,
  matkapuhelin: String,
  lahiosoite: String,
  postinumero: String,
  postitoimipaikka: Option[String],
  kotikunta: Option[String],
  asuinmaa: String,
  valintatuloksenJulkaisulupa: Boolean,
  koulutusmarkkinointilupa: Boolean,
  paymentObligations: Map[String, String],
  attachments: Map[String, String],
  eligibilities: Map[String, String],
  kkPohjakoulutus: List[String],
  kkPohjakoulutusLomake: List[String],
  korkeakoulututkintoVuosi: Option[Int]
)

case class AtaruHakemusToinenAsteDto(
  oid: String,
  personOid: String,
  createdTime: String,
  hakemusFirstSubmittedTime: String,
  kieli: String,
  hakukohteet: List[HakurekisteriHakukohde],
  email: String,
  matkapuhelin: String,
  lahiosoite: String,
  postinumero: String,
  postitoimipaikka: Option[String],
  asuinmaa: String,
  kotikunta: Option[String],
  attachments: Map[String, String],
  pohjakoulutus: String,
  kiinnostunutOppisopimusKoulutuksesta: Option[Boolean],
  sahkoisenAsioinninLupa: Boolean,
  valintatuloksenJulkaisulupa: Boolean,
  koulutusmarkkinointilupa: Boolean,
  tutkintoVuosi: Option[Int],
  tutkintoKieli: Option[String],
  huoltajat: List[GuardianContactInfo],
  urheilijanLisakysymykset: Option[UrheilijanLisakysymykset],
  urheilijanLisakysymyksetAmmatillinen: Option[UrheilijanLisakysymykset]
)

case class AtaruHakemuksenHenkilotiedot(oid: String, personOid: Option[String], ssn: Option[String])

@SerialVersionUID(1)
case class AtaruHakemus(
  oid: String,
  personOid: Option[String],
  createdTime: String,
  hakemusFirstSubmittedTime: String,
  applicationSystemId: String,
  hakutoiveet: Option[List[HakutoiveDTO]],
  henkilo: fi.vm.sade.hakurekisteri.integration.henkilo.Henkilo,
  asiointiKieli: String,
  email: String,
  matkapuhelin: String,
  lahiosoite: String,
  postinumero: String,
  postitoimipaikka: Option[String],
  kotikunta: Option[String],
  asuinmaa: String,
  julkaisulupa: Boolean,
  markkinointilupa: Boolean,
  paymentObligations: Map[String, String],
  eligibilities: Map[String, String],
  liitteetTarkastettu: Map[String, Option[Boolean]],
  kkPohjakoulutus: List[String],
  kkPohjakoulutusLomake: List[String],
  korkeakoulututkintoVuosi: Option[Int]
) extends HakijaHakemus
    with Serializable {

  val hetu: Option[String] = henkilo.hetu
  val stateValid: Boolean = true
}

case class HakutoiveenHarkinnanvaraisuus(hakukohdeOid: String, harkinnanvaraisuudenSyy: String)
case class HakemuksenHarkinnanvaraisuus(
  hakemusOid: String,
  henkiloOid: Option[String],
  hakutoiveet: List[HakutoiveenHarkinnanvaraisuus]
)

case class HakurekisteriHakukohde(
  oid: String,
  harkinnanvaraisuus: String,
  terveys: Option[Boolean],
  aiempiPeruminen: Option[Boolean],
  kiinnostunutKaksoistutkinnosta: Option[Boolean],
  kiinnostunutUrheilijanAmmatillisestaKoulutuksesta: Option[Boolean]
)

case class GuardianContactInfo(
  etunimi: Option[String],
  sukunimi: Option[String],
  matkapuhelin: Option[String],
  email: Option[String]
) {
  def nimi: Option[String] =
    if (etunimi.isDefined || sukunimi.isDefined)
      Some(etunimi.getOrElse("").concat(" ").concat(sukunimi.getOrElse("")).trim)
    else None
}

@SerialVersionUID(1)
case class AtaruHakemusToinenAste(
  oid: String,
  personOid: Option[String],
  createdTime: String, //hakemusversion luontihetki
  hakemusFirstSubmittedTime: String, //ensimmäisen hakemusversion jättöhetki
  applicationSystemId: String,
  hakutoiveet: Option[List[HakutoiveDTO]],
  henkilo: fi.vm.sade.hakurekisteri.integration.henkilo.Henkilo,
  asiointiKieli: String,
  email: String,
  matkapuhelin: String,
  lahiosoite: String,
  postinumero: String,
  postitoimipaikka: Option[String],
  kotikunta: Option[String],
  asuinmaa: String,
  attachments: Map[String, String],
  pohjakoulutus: String,
  kiinnostunutOppisopimusKoulutuksesta: Option[Boolean],
  sahkoisenAsioinninLupa: Boolean,
  valintatuloksenJulkaisulupa: Boolean,
  koulutusmarkkinointilupa: Boolean,
  tutkintoVuosi: Option[Int],
  tutkintoKieli: Option[String],
  huoltajat: List[GuardianContactInfo],
  harkinnanvaraisuudet: List[HakutoiveenHarkinnanvaraisuus],
  urheilijanLisakysymykset: Option[UrheilijanLisakysymykset]
) extends HakijaHakemus
    with Serializable {

  val hetu: Option[String] = henkilo.hetu
  val stateValid: Boolean = true
}
