package fi.vm.sade.hakurekisteri.kkhakija

import java.text.{ParseException, SimpleDateFormat}
import java.util.{Calendar, Date}

import _root_.akka.actor.{ActorRef, ActorSystem}
import _root_.akka.event.{LoggingAdapter, Logging}
import _root_.akka.pattern.ask
import _root_.akka.util.Timeout
import fi.vm.sade.hakurekisteri.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.hakija.{Hakuehto, Lasnaolo, Lasna, Poissa, Puuttuu, Syksy, Kevat}
import fi.vm.sade.hakurekisteri.hakija.Hakuehto.Hakuehto
import fi.vm.sade.hakurekisteri.integration.hakemus._
import fi.vm.sade.hakurekisteri.integration.haku.{Haku, GetHaku, HakuNotFoundException}
import fi.vm.sade.hakurekisteri.integration.koodisto.{GetRinnasteinenKoodiArvoQuery, Koodi, GetKoodi}
import fi.vm.sade.hakurekisteri.integration.tarjonta.{HakukohteenKoulutukset, HakukohdeOid, TarjontaException, Hakukohteenkoulutus}
import fi.vm.sade.hakurekisteri.integration.valintatulos._
import fi.vm.sade.hakurekisteri.integration.ytl.YTLXml
import fi.vm.sade.hakurekisteri.rest.support.{Query, User, SpringSecuritySupport, HakurekisteriJsonSupport}
import fi.vm.sade.hakurekisteri.suoritus.{SuoritysTyyppiQuery, VirallinenSuoritus, Suoritus, SuoritusQuery}
import org.scalatra.swagger.{SwaggerEngine, Swagger}
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.util.RicherString._

import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.duration._
import scala.util.Try

case class KkHakijaQuery(oppijanumero: Option[String], haku: Option[String], organisaatio: Option[String], hakukohde: Option[String], hakuehto: Hakuehto.Hakuehto, user: Option[User])

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
                   valinnanTila: Option[String],
                   vastaanottotieto: Option[String],
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
                  hakemukset: Seq[Hakemus])

object KkHakijaParamMissingException extends Exception

class KkHakijaResource(hakemukset: ActorRef,
                       tarjonta: ActorRef,
                       haut: ActorRef,
                       koodisto: ActorRef,
                       suoritukset: ActorRef,
                       valintaTulos: ActorRef)(implicit system: ActorSystem, sw: Swagger)
    extends HakuJaValintarekisteriStack with KkHakijaSwaggerApi with HakurekisteriJsonSupport with JacksonJsonSupport with FutureSupport with CorsSupport with SpringSecuritySupport {

  override protected def applicationDescription: String = "Korkeakouluhakijatietojen rajapinta"
  override protected implicit def swagger: SwaggerEngine[_] = sw
  override protected implicit def executor: ExecutionContext = system.dispatcher
  implicit val defaultTimeout: Timeout = 120.seconds

  override val logger: LoggingAdapter = Logging.getLogger(system, this)

  options("/*") {
    response.setHeader("Access-Control-Allow-Headers", request.getHeader("Access-Control-Request-Headers"))
  }

  before() {
    contentType = formats("json")
  }

  get("/", operation(query)) {
    val q = KkHakijaQuery(params, currentUser)
    logger.info("Query: " + q)
    
    if (q.oppijanumero.isEmpty && q.hakukohde.isEmpty) throw KkHakijaParamMissingException

    new AsyncResult() {
      override implicit def timeout: Duration = 120.seconds
      val is = getKkHakijat(q)
    }
  }

  incident {
    case KkHakijaParamMissingException => (id) => BadRequest(IncidentReport(id, "either parameter oppijanumero or hakukohde must be given"))
    case t: TarjontaException => (id) => InternalServerError(IncidentReport(id, s"error with tarjonta: $t"))
    case t: HakuNotFoundException => (id) => InternalServerError(IncidentReport(id, s"error: $t"))
    case t: InvalidSyntymaaikaException => (id) => InternalServerError(IncidentReport(id, s"error: $t"))
    case t: InvalidKausiException => (id) => InternalServerError(IncidentReport(id, s"error: $t"))
  }

  def getKkHakijat(q: KkHakijaQuery): Future[Seq[Hakija]] = {
    val hakemusQuery: Query[FullHakemus] with Product with Serializable = q.oppijanumero match {
      case Some(o) => HenkiloHakijaQuery(o)

      case None => HakemusQuery(q)

    }

    for {
      fullHakemukset: Seq[FullHakemus] <- (hakemukset ? hakemusQuery).mapTo[Seq[FullHakemus]]
      hakijat <- fullHakemukset2hakijat(fullHakemukset.filter(h => h.personOid.isDefined && h.stateValid))(q)
    } yield hakijat
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
    ).filter(t => t._2.exists(_ == "true")).keys.toSeq
  }

  def getValintaTieto(t: ValintaTulos, hakukohde: String)(f: (ValintaTulosHakutoive) => String): Option[String] = {
    t.hakutoiveet.withFilter(t => t.hakukohdeOid == hakukohde).map(f).headOption
  }

  // TODO muuta kun valinta-tulos-service saa ilmoittautumiset sekvenssiksi
  def getLasnaolot(t: ValintaTulos, hakukohde: String, haku: Haku, hakemusOid: String): Future[Seq[Lasnaolo]] = {
    val kausi: Future[String] = getKausi(haku.kausi, hakemusOid)

    kausi.map(k => {
      val vuosi = k match {
        case "S" => (haku.vuosi, haku.vuosi + 1)
        case "K" => (haku.vuosi, haku.vuosi)
        case _ => throw new IllegalArgumentException(s"invalid kausi $k")
      }

      def kausi(v: Int, k: String): String = s"$v$k"

      t.hakutoiveet.find(t => t.hakukohdeOid == hakukohde) match {
        case Some(h) =>
          h.ilmoittautumistila match {
            case Ilmoittautumistila.EI_TEHTY =>
              Seq(Puuttuu(Syksy(vuosi._1)), Puuttuu(Kevat(vuosi._2)))

            case Ilmoittautumistila.LASNA_KOKO_LUKUVUOSI =>
              Seq(Lasna(Syksy(vuosi._1)), Lasna(Kevat(vuosi._2)))

            case Ilmoittautumistila.POISSA_KOKO_LUKUVUOSI =>
              Seq(Poissa(Syksy(vuosi._1)), Poissa(Kevat(vuosi._2)))

            case Ilmoittautumistila.EI_ILMOITTAUTUNUT =>
              Seq(Puuttuu(Syksy(vuosi._1)), Puuttuu(Kevat(vuosi._2)))

            case Ilmoittautumistila.LASNA_SYKSY =>
              Seq(Lasna(Syksy(vuosi._1)), Poissa(Kevat(vuosi._2)))

            case Ilmoittautumistila.POISSA_SYKSY =>
              Seq(Poissa(Syksy(vuosi._1)), Lasna(Kevat(vuosi._2)))

            case Ilmoittautumistila.LASNA =>
              Seq(Lasna(Kevat(vuosi._2)))

            case Ilmoittautumistila.POISSA =>
              Seq(Poissa(Kevat(vuosi._2)))

            case _ =>
              Seq()

          }

        case None => Seq(Puuttuu(Syksy(vuosi._1)), Puuttuu(Kevat(vuosi._2)))

      }
    })
  }

  def getKausi(kausiKoodi: String, hakemusOid: String): Future[String] =
    kausiKoodi.split('#').headOption match {
      case None =>
        throw new InvalidKausiException(s"invalid kausi koodi $kausiKoodi on hakemus $hakemusOid")

      case Some(k) => (koodisto ? GetKoodi("kausi", k)).mapTo[Option[Koodi]].map {
        case None =>
          throw new InvalidKausiException(s"kausi not found with koodi $kausiKoodi on hakemus $hakemusOid")

        case Some(kausi) => kausi.koodiArvo

      }
  }

  def getHakukelpoisuus(hakukohdeOid: String, kelpoisuudet: Seq[PreferenceEligibility]): PreferenceEligibility = {
    kelpoisuudet.find(_.aoId == hakukohdeOid) match {
      case Some(h) => h

      case None =>
        val defaultState = "NOT_CHECKED"
        PreferenceEligibility(hakukohdeOid, defaultState, None)

    }
  }

  def isAuthorized(parents: Option[String], oid: Option[String]): Boolean = oid match {
    case None => true
    case Some(o) => parents.getOrElse("").split(",").toSet.contains(o)
  }

  def getKnownOrganizations(user: Option[User]):Set[String] = user.map(_.orgsFor("READ", "Hakukohde")).getOrElse(Set())

  def isAuthorized(parents: Option[String], oids: Set[String]): Boolean = {
    oids.map(o => parents.getOrElse("").split(",").toSet.contains(o)).find(_ == true).getOrElse(false)
  }

  def matchHakukohde(hakutoive: String, hakukohde: Option[String]) = hakukohde match {
    case None => true
    case Some(oid) => hakutoive == oid
  }

  def matchHakuehto(hakuehto: Hakuehto, valintaTulos: ValintaTulos, hakukohdeOid: String): Boolean = hakuehto match {
    case Hakuehto.Kaikki => true
    case Hakuehto.Hyvaksytyt => valintaTulos.hakutoiveet.find(_.hakukohdeOid == hakukohdeOid) match {
      case None => false
      case Some(h) =>
        import fi.vm.sade.hakurekisteri.integration.valintatulos.Valintatila._
        Seq[Valintatila](HYVAKSYTTY, HARKINNANVARAISESTI_HYVAKSYTTY, VARASIJALTA_HYVAKSYTTY).contains(h.valintatila)
    }
    case Hakuehto.Vastaanottaneet => valintaTulos.hakutoiveet.find(_.hakukohdeOid == hakukohdeOid) match {
      case None => false
      case Some(h) =>
        import Vastaanottotila._
        Seq[Vastaanottotila](VASTAANOTTANUT, EHDOLLISESTI_VASTAANOTTANUT).contains(h.vastaanottotila)
    }
    case Hakuehto.Hylatyt => valintaTulos.hakutoiveet.find(_.hakukohdeOid == hakukohdeOid) match {
      case None => false
      case Some(h) => h.valintatila == Valintatila.HYLATTY
    }
  }

  val Pattern = "preference(\\d+)-Koulutus-id".r

  def getHakemukset(hakemus: FullHakemus)(q: KkHakijaQuery): Future[Seq[Hakemus]] =
    Future.sequence(extract(hakemus)(q)).map(_.flatten)

  def extract(hakemus: FullHakemus)(q: KkHakijaQuery): Seq[Future[Option[Hakemus]]] =
    (for {
      answers: HakemusAnswers <- hakemus.answers
      hakutoiveet: Map[String, String] <- answers.hakutoiveet
      lisatiedot: Lisatiedot <- answers.lisatiedot
      koulutustausta: Koulutustausta <- answers.koulutustausta
    } yield hakutoiveet.keys.collect {
        case Pattern(jno) if hakutoiveet(s"preference$jno-Koulutus-id") != ""
          && matchHakukohde(hakutoiveet(s"preference$jno-Koulutus-id"), q.hakukohde)
          && isAuthorized(hakutoiveet.get(s"preference$jno-Opetuspiste-id-parents"), q.organisaatio)
          && isAuthorized(hakutoiveet.get(s"preference$jno-Opetuspiste-id-parents"), getKnownOrganizations(q.user)) =>

          val hakukohdeOid = hakutoiveet(s"preference$jno-Koulutus-id")
          val hakukelpoisuus = getHakukelpoisuus(hakukohdeOid, hakemus.preferenceEligibilities)
          for {
            valintaTulos: ValintaTulos <- (valintaTulos ? ValintaTulosQuery(hakemus.applicationSystemId, hakemus.oid, cachedOk = q.oppijanumero.isEmpty)).mapTo[ValintaTulos]
            hakukohteenkoulutukset: HakukohteenKoulutukset <- (tarjonta ? HakukohdeOid(hakukohdeOid)).mapTo[HakukohteenKoulutukset]
            haku: Haku <- (haut ? GetHaku(hakemus.applicationSystemId)).mapTo[Haku]
            kausi: String <- getKausi(haku.kausi, hakemus.oid)
            lasnaolot: Seq[Lasnaolo] <- getLasnaolot(valintaTulos, hakukohdeOid, haku, hakemus.oid)
          } yield {
            if (matchHakuehto(q.hakuehto, valintaTulos, hakukohdeOid))
              Some(Hakemus(
                haku = hakemus.applicationSystemId,
                hakuVuosi = haku.vuosi,
                hakuKausi = kausi,
                hakemusnumero = hakemus.oid,
                organisaatio = hakutoiveet(s"preference$jno-Opetuspiste-id"),
                hakukohde = hakutoiveet(s"preference$jno-Koulutus-id"),
                hakukohdeKkId = hakukohteenkoulutukset.ulkoinenTunniste,
                avoinVayla = None, // TODO valinnoista?
                valinnanTila = getValintaTieto(valintaTulos, hakukohdeOid)((t: ValintaTulosHakutoive) => t.valintatila.toString),
                vastaanottotieto = getValintaTieto(valintaTulos, hakukohdeOid)((t: ValintaTulosHakutoive) => t.vastaanottotila.toString),
                ilmoittautumiset = lasnaolot,
                pohjakoulutus = getPohjakoulutukset(koulutustausta),
                julkaisulupa = lisatiedot.lupaJulkaisu.map(_ == "true"),
                hKelpoisuus = hakukelpoisuus.status,
                hKelpoisuusLahde = hakukelpoisuus.source,
                hakukohteenKoulutukset = hakukohteenkoulutukset.koulutukset
              ))
            else None
          }
      }.toSeq).getOrElse(Seq())

  def getHakukohdeOids(hakutoiveet: Map[String, String]): Seq[String] = {
    hakutoiveet.filter((t) => t._1.endsWith("Koulutus-id") && t._2 != "").map((t) => t._2).toSeq
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

  def getMaakoodi(koodiArvo: String): Future[String] = koodiArvo.toLowerCase match {
    case "fin" => Future.successful("246")

    case arvo =>
      (koodisto ? GetRinnasteinenKoodiArvoQuery("maatjavaltiot1_" + arvo, "maatjavaltiot2")).mapTo[String]
  }

  def getToimipaikka(maa: String, postinumero: Option[String], kaupunkiUlkomaa: Option[String]): Future[String] = {
    if (maa == "246") (koodisto ? GetKoodi("posti", s"posti_${postinumero.getOrElse("00000")}")).
      mapTo[Option[Koodi]].map(getPostitoimipaikka)
    else if (kaupunkiUlkomaa.isDefined) Future.successful(kaupunkiUlkomaa.get)
    else Future.successful("")
  }

  def getKkHakija(q: KkHakijaQuery)(hakemus: FullHakemus): Option[Future[Hakija]] =
    for {
      answers: HakemusAnswers <- hakemus.answers
      henkilotiedot: HakemusHenkilotiedot <- answers.henkilotiedot
      hakutoiveet: Map[String, String] <- answers.hakutoiveet
      lisatiedot: Lisatiedot <- answers.lisatiedot
      henkiloOid <- hakemus.personOid
    } yield for {
      hakemukset <- getHakemukset(hakemus)(q)
      maa <- getMaakoodi(henkilotiedot.asuinmaa.getOrElse("FIN"))
      toimipaikka <- getToimipaikka(maa, henkilotiedot.Postinumero, henkilotiedot.kaupunkiUlkomaa)
      suoritukset <- (suoritukset ? SuoritysTyyppiQuery(henkilo = henkiloOid, komo = YTLXml.yotutkinto)).mapTo[Seq[VirallinenSuoritus]]
      kansalaisuus <- getMaakoodi(henkilotiedot.kansalaisuus.getOrElse("FIN"))
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
        kotikunta = henkilotiedot.kotikunta.getOrElse(""),
        sukupuoli = henkilotiedot.sukupuoli.getOrElse(""),
        aidinkieli = henkilotiedot.aidinkieli.getOrElse("FI"),
        asiointikieli = getAsiointikieli(henkilotiedot.aidinkieli.getOrElse("FI")),
        koulusivistyskieli = henkilotiedot.koulusivistyskieli.getOrElse("FI"),
        koulutusmarkkinointilupa = lisatiedot.lupaMarkkinointi.map(_ == "true"),
        onYlioppilas = isYlioppilas(suoritukset),
        hakemukset = hakemukset
      )

  def fullHakemukset2hakijat(hakemukset: Seq[FullHakemus])(q: KkHakijaQuery): Future[Seq[Hakija]] =
    Future.sequence(hakemukset.map(getKkHakija(q)).flatten).map(_.filter(_.hakemukset.nonEmpty))
}
