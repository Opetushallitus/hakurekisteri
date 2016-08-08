package fi.vm.sade.hakurekisteri.web.kkhakija

import java.io.OutputStream
import java.text.{ParseException, SimpleDateFormat}
import java.util.{Calendar, Date}

import akka.actor.{ActorRef, ActorSystem}
import akka.event.{Logging, LoggingAdapter}
import akka.pattern.ask
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.hakija.Hakuehto._
import fi.vm.sade.hakurekisteri.hakija.{Hakuehto, Kevat, Lasna, Lasnaolo, Poissa, Puuttuu, Syksy}
import fi.vm.sade.hakurekisteri.integration.hakemus.{FullHakemus, HakemusAnswers, HakemusHenkilotiedot, Koulutustausta, PreferenceEligibility, _}
import fi.vm.sade.hakurekisteri.integration.haku.{GetHaku, Haku, HakuNotFoundException}
import fi.vm.sade.hakurekisteri.integration.koodisto.{GetKoodi, GetRinnasteinenKoodiArvoQuery, Koodi}
import fi.vm.sade.hakurekisteri.integration.tarjonta._
import fi.vm.sade.hakurekisteri.integration.valintatulos.Valintatila.Valintatila
import fi.vm.sade.hakurekisteri.integration.valintatulos.Vastaanottotila.Vastaanottotila
import fi.vm.sade.hakurekisteri.integration.valintatulos.{Ilmoittautumistila, SijoitteluTulos, ValintaTulosQuery, Valintatila}
import fi.vm.sade.hakurekisteri.integration.ytl.YTLXml
import fi.vm.sade.hakurekisteri.rest.support._
import fi.vm.sade.hakurekisteri.suoritus.{SuoritysTyyppiQuery, VirallinenSuoritus}
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.web.kkhakija.KkHakijaUtil._
import fi.vm.sade.hakurekisteri.web.rest.support.ApiFormat.ApiFormat
import fi.vm.sade.hakurekisteri.web.rest.support.{ApiFormat, IncidentReport, _}
import org.joda.time.DateTime
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.{Swagger, SwaggerEngine}
import org.scalatra.util.RicherString._

import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.util.Try

case class KkHakijaQuery(oppijanumero: Option[String],
                         haku: Option[String],
                         organisaatio: Option[String],
                         hakukohde: Option[String],
                         hakuehto: Hakuehto.Hakuehto,
                         user: Option[User])

object KkHakijaQuery {
  def apply(params: Map[String,String], currentUser: Option[User]): KkHakijaQuery = new KkHakijaQuery(
    oppijanumero = params.get("oppijanumero").flatMap(_.blankOption),
    haku = params.get("haku").flatMap(_.blankOption),
    organisaatio = params.get("organisaatio").flatMap(_.blankOption),
    hakukohde = params.get("hakukohde").flatMap(_.blankOption),
    hakuehto = Try(Hakuehto.withName(params("hakuehto"))).recover{ case _ => Hakuehto.Kaikki }.get,
    user = currentUser
  )
}

case class InvalidSyntymaaikaException(m: String) extends Exception(m)
case class InvalidKausiException(m: String) extends Exception(m)

case class Hakemus(haku: String,
                   hakuVuosi: Int,
                   hakuKausi: String,
                   hakemusnumero: String,
                   organisaatio: String,
                   hakukohde: String,
                   hakukohdeKkId: Option[String],
                   avoinVayla: Option[Boolean],
                   valinnanTila: Option[Valintatila],
                   vastaanottotieto: Option[Vastaanottotila],
                   ilmoittautumiset: Seq[Lasnaolo],
                   pohjakoulutus: Seq[String],
                   julkaisulupa: Option[Boolean],
                   hKelpoisuus: String,
                   hKelpoisuusLahde: Option[String],
                   hakukohteenKoulutukset: Seq[Hakukohteenkoulutus])

case class Hakija(hetu: String,
                  oppijanumero: String,
                  sukunimi: String,
                  etunimet: String,
                  kutsumanimi: String,
                  lahiosoite: String,
                  postinumero: String,
                  postitoimipaikka: String,
                  maa: String,
                  kansalaisuus: String,
                  matkapuhelin: Option[String],
                  puhelin: Option[String],
                  sahkoposti: Option[String],
                  kotikunta: String,
                  sukupuoli: String,
                  aidinkieli: String,
                  asiointikieli: String,
                  koulusivistyskieli: String,
                  koulutusmarkkinointilupa: Option[Boolean],
                  onYlioppilas: Boolean,
                  turvakielto: Boolean,
                  hakemukset: Seq[Hakemus])

object KkHakijaParamMissingException extends Exception

class KkHakijaResource(hakemusService: HakemusService,
                       tarjonta: ActorRef,
                       haut: ActorRef,
                       koodisto: ActorRef,
                       suoritukset: ActorRef,
                       valintaTulos: ActorRef)(implicit system: ActorSystem, sw: Swagger, val security: Security, val ct: ClassTag[Seq[Hakija]])
    extends HakuJaValintarekisteriStack with KkHakijaSwaggerApi with HakurekisteriJsonSupport with JacksonJsonSupport with FutureSupport
    with SecuritySupport with ExcelSupport[Seq[Hakija]] with DownloadSupport with QueryLogging {

  protected def applicationDescription: String = "Korkeakouluhakijatietojen rajapinta"
  protected implicit def swagger: SwaggerEngine[_] = sw
  override protected implicit def executor: ExecutionContext = system.dispatcher
  implicit val defaultTimeout: Timeout = 120.seconds

  override val logger: LoggingAdapter = Logging.getLogger(system, this)

  def getContentType(t: ApiFormat): String = t match {
    case ApiFormat.Json => formats("json")
    case ApiFormat.Excel => formats("binary")
    case tyyppi => throw new IllegalArgumentException(s"tyyppi $tyyppi is not supported")
  }

  override protected def renderPipeline: RenderPipeline = renderExcel orElse super.renderPipeline
  override val streamingRender: (OutputStream, Seq[Hakija]) => Unit = KkExcelUtil.write

  get("/", operation(query)) {
    val t0 = Platform.currentTime
    val q = KkHakijaQuery(params, currentUser)

    val tyyppi = Try(ApiFormat.withName(params("tyyppi"))).getOrElse(ApiFormat.Json)
    contentType = getContentType(tyyppi)

    if (q.oppijanumero.isEmpty && q.hakukohde.isEmpty) throw KkHakijaParamMissingException

    new AsyncResult() {
      override implicit def timeout: Duration = 120.seconds
      val res = getKkHakijat(q)

      val kkhakijatFuture = res.flatMap {
        case result if Try(params("tiedosto").toBoolean).getOrElse(false) || tyyppi == ApiFormat.Excel =>
          setContentDisposition(tyyppi, response, "hakijat")
          Future.successful(result)
        case result => Future.successful(result)
      }

      logQuery(q, t0, kkhakijatFuture)

      val is = kkhakijatFuture
    }
  }

  incident {
    case KkHakijaParamMissingException => (id) => BadRequest(IncidentReport(id, "either parameter oppijanumero or hakukohde must be given"))
    case t: TarjontaException => (id) => InternalServerError(IncidentReport(id, s"error with tarjonta: $t"))
    case t: HakuNotFoundException => (id) => NotFound(IncidentReport(id, s"$t"))
    case t: InvalidSyntymaaikaException => (id) => InternalServerError(IncidentReport(id, s"error: $t"))
    case t: InvalidKausiException => (id) => InternalServerError(IncidentReport(id, s"error: $t"))
  }

  def getKkHakijat(q: KkHakijaQuery): Future[Seq[Hakija]] = {
    def matchHakemusToQuery(hakemus: FullHakemus) : Boolean = {
      hakemus.personOid.isDefined && hakemus.stateValid &&
        q.oppijanumero.forall(hakemus.personOid.contains(_)) &&
        q.haku.forall(_ == hakemus.applicationSystemId)
    }

    for (
      hakemukset <- q match {
        case KkHakijaQuery(Some(oppijanumero), _, _, _, _, _) => hakemusService.hakemuksetForPerson(oppijanumero)
        case KkHakijaQuery(None, _, _, Some(hakukohde), _, _) => hakemusService.hakemuksetForHakukohde(hakukohde, q.organisaatio)
        case _ => Future.failed(KkHakijaParamMissingException)
      };
      hakijat <- fullHakemukset2hakijat(hakemukset.filter(matchHakemusToQuery))(q)
    ) yield hakijat
  }

  private def getHakukelpoisuus(hakukohdeOid: String, kelpoisuudet: Seq[PreferenceEligibility]): PreferenceEligibility = {
    kelpoisuudet.find(_.aoId == hakukohdeOid) match {
      case Some(h) => h

      case None =>
        val defaultState = ""
        PreferenceEligibility(hakukohdeOid, defaultState, None)

    }
  }

  private def isAuthorized(parents: Option[String], oid: Option[String]): Boolean = oid match {
    case None => true
    case Some(o) => parents.getOrElse("").split(",").toSet.contains(o)
  }

  private def getKnownOrganizations(user: Option[User]):Set[String] = user.map(_.orgsFor("READ", "Hakukohde")).getOrElse(Set())

  private def isAuthorized(parents: Option[String], oids: Set[String]): Boolean = {
    oids.map(o => parents.getOrElse("").split(",").toSet.contains(o)).find(_ == true).getOrElse(false)
  }

  private def matchHakukohde(hakutoive: String, hakukohde: Option[String]) = hakukohde match {
    case None => true
    case Some(oid) => hakutoive == oid
  }

  import fi.vm.sade.hakurekisteri.integration.valintatulos.Valintatila.isHyvaksytty
  import fi.vm.sade.hakurekisteri.integration.valintatulos.Vastaanottotila.isVastaanottanut

  private def matchHakuehto(valintaTulos: SijoitteluTulos, hakemusOid: String, hakukohdeOid: String): (Hakuehto) => Boolean = {
    case Hakuehto.Kaikki => true
    case Hakuehto.Hyvaksytyt => matchHyvaksytyt(valintaTulos, hakemusOid, hakukohdeOid)
    case Hakuehto.Vastaanottaneet => matchVastaanottaneet(valintaTulos, hakemusOid, hakukohdeOid)
    case Hakuehto.Hylatyt => matchHylatyt(valintaTulos, hakemusOid, hakukohdeOid)
  }

  private def matchHylatyt(valintaTulos: SijoitteluTulos, hakemusOid: String, hakukohdeOid: String): Boolean = {
    valintaTulos.valintatila(hakemusOid, hakukohdeOid) match {
      case Some(t) => t == Valintatila.HYLATTY
      case _ => false
    }
  }

  private def matchVastaanottaneet(valintaTulos: SijoitteluTulos, hakemusOid: String, hakukohdeOid: String): Boolean = {
    valintaTulos.vastaanottotila(hakemusOid, hakukohdeOid) match {
      case Some(t) => isVastaanottanut(t)
      case _ => false
    }
  }

  private def matchHyvaksytyt(valintaTulos: SijoitteluTulos, hakemusOid: String, hakukohdeOid: String): Boolean = {
    valintaTulos.valintatila(hakemusOid, hakukohdeOid) match {
      case Some(t) => isHyvaksytty(t)
      case _ => false
    }
  }

  private val Pattern = "preference(\\d+)-Koulutus-id".r

  private def getValintaTulos(q: ValintaTulosQuery): Future[SijoitteluTulos] = (valintaTulos ? q).mapTo[SijoitteluTulos]

  private def getHakemukset(haku: Haku, hakemus: FullHakemus, q: KkHakijaQuery, kokoHaunTulos: Option[SijoitteluTulos]): Future[Seq[Hakemus]] = {
    val valintaTulosQuery = q.oppijanumero match {
      case Some(o) => ValintaTulosQuery(hakemus.applicationSystemId, Some(hakemus.oid), cachedOk = false)
      case None => ValintaTulosQuery(hakemus.applicationSystemId, None)
    }

    kokoHaunTulos.map(Future.successful)
      .getOrElse(getValintaTulos(valintaTulosQuery))
      .flatMap(tulos => Future.sequence(extractHakemukset(hakemus, q, haku, tulos)).map(_.flatten))
  }

  private def extractHakemukset(hakemus: FullHakemus,
                        q: KkHakijaQuery,
                        haku: Haku,
                        sijoitteluTulos: SijoitteluTulos): Seq[Future[Option[Hakemus]]] =
    (for {
      answers: HakemusAnswers <- hakemus.answers
      hakutoiveet: Map[String, String] <- answers.hakutoiveet
    } yield hakutoiveet.keys.collect {
        case Pattern(jno: String) if hakutoiveet(s"preference$jno-Koulutus-id") != "" && queryMatches(q, hakutoiveet, jno) =>
          extractSingleHakemus(
            hakemus,
            q,
            hakutoiveet,
            answers.lisatiedot.getOrElse(Map()),
            answers.koulutustausta.getOrElse(Koulutustausta()),
            jno,
            haku,
            sijoitteluTulos
          )
      }.toSeq).getOrElse(Seq())

  private def queryMatches(q: KkHakijaQuery, hakutoiveet: Map[String, String], jno: String): Boolean = {
    matchHakukohde(hakutoiveet(s"preference$jno-Koulutus-id"), q.hakukohde) &&
      isAuthorized(hakutoiveet.get(s"preference$jno-Opetuspiste-id-parents"), q.organisaatio) &&
      isAuthorized(hakutoiveet.get(s"preference$jno-Opetuspiste-id-parents"), getKnownOrganizations(q.user))
  }

  private def extractSingleHakemus(hakemus: FullHakemus,
                           q: KkHakijaQuery,
                           hakutoiveet: Map[String, String],
                           lisatiedot: Map[String, String],
                           koulutustausta: Koulutustausta,
                           jno: String,
                           haku: Haku,
                           sijoitteluTulos: SijoitteluTulos): Future[Option[Hakemus]] = {
    val hakemusOid = hakemus.oid
    val hakukohdeOid = hakutoiveet(s"preference$jno-Koulutus-id")
    val hakukelpoisuus = getHakukelpoisuus(hakukohdeOid, hakemus.preferenceEligibilities)

    for {
      hakukohteenkoulutukset: HakukohteenKoulutukset <- (tarjonta ? HakukohdeOid(hakukohdeOid)).mapTo[HakukohteenKoulutukset]
      kausi: String <- getKausi(haku.kausi, hakemusOid, koodisto)
      lasnaolot: Seq[Lasnaolo] <- getLasnaolot(sijoitteluTulos, hakukohdeOid, hakemusOid, hakukohteenkoulutukset.koulutukset)
    } yield {
      if (matchHakuehto(sijoitteluTulos, hakemusOid, hakukohdeOid)(q.hakuehto)) {
        Some(Hakemus(
          haku = hakemus.applicationSystemId,
          hakuVuosi = haku.vuosi,
          hakuKausi = kausi,
          hakemusnumero = hakemusOid,
          organisaatio = hakutoiveet(s"preference$jno-Opetuspiste-id"),
          hakukohde = hakutoiveet(s"preference$jno-Koulutus-id"),
          hakukohdeKkId = hakukohteenkoulutukset.ulkoinenTunniste,
          avoinVayla = None, // TODO valinnoista?
          valinnanTila = sijoitteluTulos.valintatila(hakemusOid, hakukohdeOid),
          vastaanottotieto = sijoitteluTulos.vastaanottotila(hakemusOid, hakukohdeOid),
          ilmoittautumiset = lasnaolot,
          pohjakoulutus = getPohjakoulutukset(koulutustausta),
          julkaisulupa = lisatiedot.get("lupaJulkaisu").map(_ == "true"),
          hKelpoisuus = hakukelpoisuus.status,
          hKelpoisuusLahde = hakukelpoisuus.source,
          hakukohteenKoulutukset = hakukohteenkoulutukset.koulutukset
            .map(koulutus => koulutus.copy(koulutuksenAlkamiskausi = None, koulutuksenAlkamisvuosi = None, koulutuksenAlkamisPvms = None))
        ))
      } else {
        None
      }
    }
  }

  private def getKkHakija(haku: Haku, q: KkHakijaQuery, kokoHaunTulos: Option[SijoitteluTulos])(hakemus: FullHakemus): Option[Future[Hakija]] =
    for {
      answers: HakemusAnswers <- hakemus.answers
      henkilotiedot: HakemusHenkilotiedot <- answers.henkilotiedot
      hakutoiveet: Map[String, String] <- answers.hakutoiveet
      henkiloOid <- hakemus.personOid
    } yield for {
      hakemukset <- getHakemukset(haku, hakemus, q, kokoHaunTulos)
      maa <- getMaakoodi(henkilotiedot.asuinmaa.getOrElse("FIN"), koodisto)
      toimipaikka <- getToimipaikka(maa, henkilotiedot.Postinumero, henkilotiedot.kaupunkiUlkomaa, koodisto)
      suoritukset <- (suoritukset ? SuoritysTyyppiQuery(henkilo = henkiloOid, komo = YTLXml.yotutkinto)).mapTo[Seq[VirallinenSuoritus]]
      kansalaisuus <- getMaakoodi(henkilotiedot.kansalaisuus.getOrElse("FIN"), koodisto)
    } yield Hakija(
        hetu = getHetu(henkilotiedot.Henkilotunnus, henkilotiedot.syntymaaika, hakemus.oid),
        oppijanumero = hakemus.personOid.getOrElse(""),
        sukunimi = henkilotiedot.Sukunimi.getOrElse(""),
        etunimet = henkilotiedot.Etunimet.getOrElse(""),
        kutsumanimi = henkilotiedot.Kutsumanimi.getOrElse(""),
        lahiosoite = henkilotiedot.lahiosoite.flatMap(_.blankOption).
          getOrElse(henkilotiedot.osoiteUlkomaa.getOrElse("")),
        postinumero = henkilotiedot.Postinumero.flatMap(_.blankOption).
          getOrElse(henkilotiedot.postinumeroUlkomaa.getOrElse("")),
        postitoimipaikka = toimipaikka,
        maa = maa,
        kansalaisuus = kansalaisuus,
        matkapuhelin = henkilotiedot.matkapuhelinnumero1.flatMap(_.blankOption),
        puhelin = henkilotiedot.matkapuhelinnumero2.flatMap(_.blankOption),
        sahkoposti = henkilotiedot.Sähköposti.flatMap(_.blankOption),
        kotikunta = henkilotiedot.kotikunta.flatMap(_.blankOption).getOrElse("200"),
        sukupuoli = henkilotiedot.sukupuoli.getOrElse(""),
        aidinkieli = henkilotiedot.aidinkieli.getOrElse("FI"),
        asiointikieli = getAsiointikieli(henkilotiedot.aidinkieli.getOrElse("FI")),
        koulusivistyskieli = henkilotiedot.koulusivistyskieli.getOrElse("FI"),
        koulutusmarkkinointilupa = answers.lisatiedot.getOrElse(Map()).get("lupaMarkkinointi").map(_ == "true"),
        onYlioppilas = isYlioppilas(suoritukset),
        turvakielto = henkilotiedot.turvakielto.contains("true"),
        hakemukset = hakemukset
      )

  private def fullHakemukset2hakijat(hakemukset: Seq[FullHakemus])(q: KkHakijaQuery): Future[Seq[Hakija]] = {
    Future.sequence(hakemukset.groupBy(_.applicationSystemId).map {
      case (hakuOid, h) =>
        (haut ? GetHaku(hakuOid)).mapTo[Haku].flatMap(haku =>
          if (haku.kkHaku) {
            if (q.oppijanumero.isEmpty) {
              getValintaTulos(ValintaTulosQuery(hakuOid, None)).flatMap(kokoHaunTulos =>
                Future.sequence(h.map(getKkHakija(haku, q, Some(kokoHaunTulos))).flatten).map(_.filter(_.hakemukset.nonEmpty))
              )
            } else {
              Future.sequence(h.map(getKkHakija(haku, q, None)).flatten).map(_.filter(_.hakemukset.nonEmpty))
            }
          } else {
            Future.successful(Seq())
          }
        ).recoverWith {
          case e: HakuNotFoundException => Future.successful(Seq())
        }
    }.toSeq).map(_.foldLeft(Seq[Hakija]())(_ ++ _)).map(_.filter(_.hakemukset.nonEmpty))
  }
}

object KkHakijaUtil {
  def getHakukohdeOids(hakutoiveet: Map[String, String]): Seq[String] = {
    hakutoiveet.filter((t) => t._1.endsWith("Koulutus-id") && t._2 != "").values.toSeq
  }

  def toKkSyntymaaika(d: Date): String = {
    val c = Calendar.getInstance()
    c.setTime(d)
    new SimpleDateFormat("ddMMyy").format(d) + (c.get(Calendar.YEAR) match {
      case y if y >= 2000 => "A"
      case y if y >= 1900 && y < 2000 => "-"
      case _ => ""
    })
  }

  def getHetu(hetu: Option[String], syntymaaika: Option[String], hakemusnumero: String): String = hetu match {
    case Some(h) => h

    case None => syntymaaika match {
      case Some(s) =>
        try {
          toKkSyntymaaika(new SimpleDateFormat("dd.MM.yyyy").parse(s))
        } catch {
          case t: ParseException =>
            throw InvalidSyntymaaikaException(s"could not parse syntymäaika $s in hakemus $hakemusnumero")
        }

      case None =>
        throw InvalidSyntymaaikaException(s"syntymäaika and hetu missing from hakemus $hakemusnumero")

    }
  }

  def getAsiointikieli(kielikoodi: String): String = kielikoodi match {
    case "FI" => "1"
    case "SV" => "2"
    case "EN" => "3"
    case _ => "9"
  }

  def getPostitoimipaikka(koodi: Option[Koodi]): String = koodi match {
    case None => ""

    case Some(k) => k.metadata.find(_.kieli == "FI") match {
      case None => ""

      case Some(m) => m.nimi
    }
  }

  def isYlioppilas(suoritukset: Seq[VirallinenSuoritus]): Boolean = suoritukset.exists(s => s.tila == "VALMIS" && s.vahvistettu)

  def getMaakoodi(koodiArvo: String, koodisto: ActorRef)(implicit timeout: Timeout): Future[String] = koodiArvo.toLowerCase match {
    case "fin" => Future.successful("246")

    case arvo =>
      (koodisto ? GetRinnasteinenKoodiArvoQuery("maatjavaltiot1_" + arvo, "maatjavaltiot2")).mapTo[String]
  }

  def getToimipaikka(maa: String, postinumero: Option[String], kaupunkiUlkomaa: Option[String], koodisto: ActorRef)
                    (implicit timeout: Timeout, ec: ExecutionContext): Future[String] = {
    if (maa == "246") {
      (koodisto ? GetKoodi("posti", s"posti_${postinumero.getOrElse("00000")}")).
        mapTo[Option[Koodi]].map(getPostitoimipaikka)
    } else if (kaupunkiUlkomaa.isDefined) {
      Future.successful(kaupunkiUlkomaa.get)
    } else {
      Future.successful("")
    }
  }

  def getKausi(kausiKoodi: String, hakemusOid: String, koodisto: ActorRef)
              (implicit timeout: Timeout, ec: ExecutionContext): Future[String] =
    kausiKoodi.split('#').headOption match {
      case None =>
        throw new InvalidKausiException(s"invalid kausi koodi $kausiKoodi on hakemus $hakemusOid")

      case Some(k) => (koodisto ? GetKoodi("kausi", k)).mapTo[Option[Koodi]].map {
        case None =>
          throw new InvalidKausiException(s"kausi not found with koodi $kausiKoodi on hakemus $hakemusOid")

        case Some(kausi) => kausi.koodiArvo

      }
    }

  def getPohjakoulutukset(k: Koulutustausta): Seq[String] = {
    Map(
      "yo" -> k.pohjakoulutus_yo,
      "am" -> k.pohjakoulutus_am,
      "amt" -> k.pohjakoulutus_amt,
      "kk" -> k.pohjakoulutus_kk,
      "ulk" -> k.pohjakoulutus_ulk,
      "avoin" -> k.pohjakoulutus_avoin,
      "muu" -> k.pohjakoulutus_muu
    ).collect{ case (key, Some("true")) => key }.toSeq
  }

  // TODO muuta kun valinta-tulos-service saa ilmoittautumiset sekvenssiksi

  /**
    * Return a Future of Sequence of Lasnaolos based on conversions from season/year information from Hakukohteenkoulutus
    *
    * Uses first of the Hakukohteenkoulutus that has valid data when determining start season/year of the Lasnaolo.
    * Hakukohteenkoulutus might have the information in a Long date or separately as season and year.
    * Prefer season and year, if they aren't present, try parsing first of the possible Long dates.
    */
  def getLasnaolot(t: SijoitteluTulos, hakukohde: String, hakemusOid: String, koulutukset: Seq[Hakukohteenkoulutus])
                  (implicit timeout: Timeout, ec: ExecutionContext): Future[Seq[Lasnaolo]] = {

    koulutukset.find(koulutusHasValidFieldsForParsing)
               .map(parseKausiVuosiPair)
               .map(kausiVuosiPairToLasnaoloSequenceFuture(_:(String, Int), t, hakemusOid, hakukohde))
               .get
  }

  /**
    * Use sijoittelunTulos and parsed kausiVuosiPair to determine Lasnaolos.
    */
  private def kausiVuosiPairToLasnaoloSequenceFuture(kvp: (String, Int),
                                                     sijoittelunTulos:SijoitteluTulos,
                                                     hakemusOid: String,
                                                     hakukohde: String)
                                                    (implicit timeout: Timeout, ec: ExecutionContext):Future[Seq[Lasnaolo]] = {

    val lukuvuosi: (Int, Int) = kausiVuosiToLukuvuosiPair(kvp)

    Future(sijoittelunTulos.ilmoittautumistila(hakemusOid, hakukohde).map {
      case Ilmoittautumistila.EI_TEHTY              => Seq(Puuttuu(Syksy(lukuvuosi._1)), Puuttuu(Kevat(lukuvuosi._2)))
      case Ilmoittautumistila.LASNA_KOKO_LUKUVUOSI  => Seq(Lasna(Syksy(lukuvuosi._1)), Lasna(Kevat(lukuvuosi._2)))
      case Ilmoittautumistila.POISSA_KOKO_LUKUVUOSI => Seq(Poissa(Syksy(lukuvuosi._1)), Poissa(Kevat(lukuvuosi._2)))
      case Ilmoittautumistila.EI_ILMOITTAUTUNUT     => Seq(Puuttuu(Syksy(lukuvuosi._1)), Puuttuu(Kevat(lukuvuosi._2)))
      case Ilmoittautumistila.LASNA_SYKSY           => Seq(Lasna(Syksy(lukuvuosi._1)), Poissa(Kevat(lukuvuosi._2)))
      case Ilmoittautumistila.POISSA_SYKSY          => Seq(Poissa(Syksy(lukuvuosi._1)), Lasna(Kevat(lukuvuosi._2)))
      case Ilmoittautumistila.LASNA                 => Seq(Lasna(Kevat(lukuvuosi._2)))
      case Ilmoittautumistila.POISSA                => Seq(Poissa(Kevat(lukuvuosi._2)))
      case _                                        => Seq()
    }.getOrElse(Seq(Puuttuu(Syksy(lukuvuosi._1)), Puuttuu(Kevat(lukuvuosi._2)))))

  }

  /**
    * Returns a Pair(Season, Year) or throws an Exception
    *
    * Used for determining Lasnaolos.
    */
  private def parseKausiVuosiPair(k: Hakukohteenkoulutus): (String, Int) = {

    val kausi: Option[TarjontaKoodi] = k.koulutuksenAlkamiskausi
    val vuosi: Int                   = k.koulutuksenAlkamisvuosi.getOrElse(0)
    val pvms:  Option[Set[Long]]     = k.koulutuksenAlkamisPvms

    if (kausi.isDefined && kausi.get.arvo.nonEmpty && vuosi != 0) {
      (kausi.get.arvo.get, vuosi)
    } else {
      datetimeLongToKausiVuosiPair(
        pvms.getOrElse(Set()).collectFirst{case pvm: Long => pvm}
            .getOrElse(throw new scala.IllegalArgumentException("Invalid Hakukohteenkoulutus data. Could not parse Lasnaolos."))
      )
    }

  }

  /**
    * Returns true if Hakukohteenkoulutus has valid fields for parsing a start season/year
    */
  private def koulutusHasValidFieldsForParsing(k: Hakukohteenkoulutus): Boolean = {

    if (k.koulutuksenAlkamiskausi.isDefined &&
        k.koulutuksenAlkamiskausi.get.arvo.nonEmpty &&
        k.koulutuksenAlkamisvuosi.getOrElse(0) != 0 ||
        k.koulutuksenAlkamisPvms.isDefined &&
        k.koulutuksenAlkamisPvms.get.nonEmpty) {
      true
    } else {
      false
    }

  }

  /**
    * Return Pair of years for autumn and spring seasons from passed in
    * Pair containing start season and year of the learning opportunity.
    */
  private def kausiVuosiToLukuvuosiPair(kv: (String, Int)): (Int, Int) = {
    kv._1 match {
      case "S" => (kv._2, kv._2 + 1)
      case "K" => (kv._2, kv._2)
      case _ => throw new scala.IllegalArgumentException("Invalid kausi " + kv._1)
    }
  }

  /**
    * Convert date in millis to a datetime and extract year/season
    * information from it as a Pair.
    */
  private def datetimeLongToKausiVuosiPair(d: Long): (String, Int) = {
    val date: DateTime = new DateTime(d)
    val year: Int = date.getYear
    val kausi: String = date.getMonthOfYear match {
      case m if m >= 1 && m <= 7  => "K"   // 1.1 - 31.7    Spring season
      case m if m >= 8 && m <= 12 => "S"   // 1.8 - 31.12   Autumn season
      case _ => throw new scala.IllegalArgumentException("Invalid date provided.")
    }
    (kausi, year)
  }
}
