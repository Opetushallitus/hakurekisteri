package fi.vm.sade.hakurekisteri.web.kkhakija

import java.text.{ParseException, SimpleDateFormat}
import java.time.Instant
import java.util.{Calendar, Date}

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.hakija.Hakuehto._
import fi.vm.sade.hakurekisteri.hakija.{Hakuehto, Kevat, Lasna, Lasnaolo, Poissa, Puuttuu, Syksy}
import fi.vm.sade.hakurekisteri.integration.hakemus.{FullHakemus, HakemusAnswers, HakemusHenkilotiedot, Koulutustausta, PreferenceEligibility, _}
import fi.vm.sade.hakurekisteri.integration.haku.{GetHaku, Haku, HakuNotFoundException}
import fi.vm.sade.hakurekisteri.integration.koodisto.{GetKoodi, GetRinnasteinenKoodiArvoQuery, Koodi}
import fi.vm.sade.hakurekisteri.integration.tarjonta._
import fi.vm.sade.hakurekisteri.integration.valintarekisteri.{Lukuvuosimaksu, LukuvuosimaksuQuery, Maksuntila}
import fi.vm.sade.hakurekisteri.integration.valintatulos.Valintatila.Valintatila
import fi.vm.sade.hakurekisteri.integration.valintatulos.Vastaanottotila.Vastaanottotila
import fi.vm.sade.hakurekisteri.integration.valintatulos.{Ilmoittautumistila, SijoitteluTulos, ValintaTulosQuery, Valintatila}
import fi.vm.sade.hakurekisteri.integration.ytl.YoTutkinto
import fi.vm.sade.hakurekisteri.rest.support._
import fi.vm.sade.hakurekisteri.suoritus.{SuoritysTyyppiQuery, VirallinenSuoritus}
import fi.vm.sade.hakurekisteri.web.kkhakija.KkHakijaUtil._
import org.joda.time.DateTime
import org.scalatra.util.RicherString._
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Try}

case class KkHakijaQuery(oppijanumero: Option[String],
                         haku: Option[String],
                         organisaatio: Option[String],
                         hakukohde: Option[String],
                         hakukohderyhma: Option[String],
                         hakuehto: Hakuehto.Hakuehto,
                         version: Int,
                         user: Option[User]) extends Query {

}

object KkHakijaQuery {
  def apply(params: Map[String,String], currentUser: Option[User]): KkHakijaQuery = new KkHakijaQuery(
    oppijanumero = params.get("oppijanumero").flatMap(_.blankOption),
    haku = params.get("haku").flatMap(_.blankOption),
    organisaatio = params.get("organisaatio").flatMap(_.blankOption),
    hakukohde = params.get("hakukohde").flatMap(_.blankOption),
    hakukohderyhma = params.get("hakukohderyhma").flatMap(_.blankOption),
    hakuehto = Try(Hakuehto.withName(params("hakuehto"))).recover{ case _ => Hakuehto.Kaikki }.get,
    version = Try(params("version").toInt).recover{ case _ => 2 }.get,
    user = currentUser
  )
}

case class InvalidSyntymaaikaException(m: String) extends Exception(m)
case class InvalidKausiException(m: String) extends Exception(m)

case class Liite(hakuId: String,
                 hakuRyhmaId: String,
                 tila: String,
                 saapumisenTila: String,
                 nimi: String,
                 vastaanottaja: String)

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
                   hKelpoisuusMaksuvelvollisuus: Option[String],
                   lukuvuosimaksu: Option[String],
                   hakukohteenKoulutukset: Seq[Hakukohteenkoulutus],
                   liitteet: Option[Seq[Liite]])

case class Hakija(hetu: String,
                  oppijanumero: String,
                  sukunimi: String,
                  etunimet: String,
                  kutsumanimi: String,
                  lahiosoite: String,
                  postinumero: String,
                  postitoimipaikka: String,
                  maa: String,
                  kansalaisuus: Option[String],
                  kaksoiskansalaisuus: Option[String],
                  kansalaisuudet: Option[List[String]],
                  syntymaaika: Option[String],
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
                  yoSuoritusVuosi: Option[String],
                  turvakielto: Boolean,
                  hakemukset: Seq[Hakemus])

object KkHakijaParamMissingException extends Exception

class KkHakijaService(hakemusService: IHakemusService,
                      hakupalvelu: Hakupalvelu,
                      tarjonta: ActorRef,
                      haut: ActorRef,
                      koodisto: ActorRef,
                      suoritukset: ActorRef,
                      valintaTulos: ActorRef,
                      valintaRekisteri: ActorRef,
                      valintaTulosTimeout: Timeout)(implicit system: ActorSystem) {
  implicit val defaultTimeout: Timeout = 120.seconds
  implicit def executor: ExecutionContext = system.dispatcher

  def getKkHakijat(q: KkHakijaQuery, version: Int): Future[Seq[Hakija]] = {
    def resolveMultipleHakukohdeOidsAsHakemukset(hakukohdeOids: Seq[String]): Future[Seq[HakijaHakemus]] = {
      hakemusService.hakemuksetForHakukohdes(hakukohdeOids.toSet, q.organisaatio)
    }

    def matchHakemusToQuery(hakemus: HakijaHakemus): Boolean = {
      hakemus.personOid.isDefined && hakemus.stateValid &&
        q.oppijanumero.forall(hakemus.personOid.contains(_)) &&
        q.haku.forall(_ == hakemus.applicationSystemId)
    }

    for (
      hakemukset <- q match {
        case KkHakijaQuery(Some(oppijanumero), _, _, _, _, _, _, _) => hakemusService.hakemuksetForPerson(oppijanumero)
        case KkHakijaQuery(None, _, _, Some(hakukohde), _, _, _, _) => hakemusService.hakemuksetForHakukohde(hakukohde, q.organisaatio)
        case KkHakijaQuery(None, Some(haku), _, None, Some(hakukohderyhma), _, _, _) =>
          hakupalvelu.getHakukohdeOids(hakukohderyhma, haku).flatMap(resolveMultipleHakukohdeOidsAsHakemukset)
        case _ => Future.failed(KkHakijaParamMissingException)
      };
      hakijat <- fullHakemukset2hakijat(hakemukset.filter(matchHakemusToQuery), version)(q)
    ) yield hakijat
  }

  private val logger = LoggerFactory.getLogger(this.getClass)

  private def fullHakemukset2hakijat(hakemukset: Seq[HakijaHakemus], version: Int)(q: KkHakijaQuery): Future[Seq[Hakija]] = {
    val fullHakemusesByHakuOid: Map[String, Seq[HakijaHakemus]] = hakemukset.groupBy(_.applicationSystemId)
    Future.sequence(fullHakemusesByHakuOid.map {
      case (hakuOid, fullHakemuses) =>
        (haut ? GetHaku(hakuOid)).mapTo[Haku].flatMap(haku =>
          if (haku.kkHaku) {
            q.hakukohderyhma.map(hakupalvelu.getHakukohdeOids(_, haku.oid)).getOrElse(Future.successful(Seq())).flatMap(hakukohdeOids => {
              version match {
                case 1 =>
                  kokoHaunTulosIfNoOppijanumero(q, hakuOid).flatMap { kokoHaunTulos =>
                    Future.sequence(fullHakemuses.flatMap(getKkHakijaV1(haku, q, kokoHaunTulos, hakukohdeOids))).map(_.filter(_.hakemukset.nonEmpty))
                  }
                case 2 =>
                  createV2Hakijas(q, fullHakemuses, haku, hakukohdeOids)
                case 3 =>
                  createV3Hakijas(q, fullHakemuses, haku, hakukohdeOids)
              }
            })
          } else {
            Future.successful(Seq())
          }
        ).recoverWith {
          case e: HakuNotFoundException => Future.successful(Seq())
        }
    }.toSeq).map(_.foldLeft(Seq[Hakija]())(_ ++ _)).map(_.filter(_.hakemukset.nonEmpty))
  }


  private def createV2Hakijas(q: KkHakijaQuery, hakemukset: Seq[HakijaHakemus], haku: Haku, hakukohdeOids: Seq[String]) = {
    val maksuvelvollisuudet: Set[String] = hakemukset.flatMap(_ match {
      case h: FullHakemus => h.preferenceEligibilities.filter(_.maksuvelvollisuus.isDefined).map(_.aoId)
      case h: AtaruHakemus => h.paymentObligations.filter(_._2 == "REQUIRED").keys
    }).toSet

    getLukuvuosimaksut(maksuvelvollisuudet, q.user.get.auditSession()).flatMap(lukuvuosimaksut => {
      kokoHaunTulosIfNoOppijanumero(q, haku.oid).flatMap { kokoHaunTulos =>
        val maksusByHakijaAndHakukohde = lukuvuosimaksut.groupBy(_.personOid).mapValues(_.toList.groupBy(_.hakukohdeOid))
        Future.sequence(hakemukset.flatMap(getKkHakijaV2(haku, q, kokoHaunTulos, hakukohdeOids, maksusByHakijaAndHakukohde)))
          .map(_.filter(_.hakemukset.nonEmpty))
      }
    })
  }

  private def createV3Hakijas(q: KkHakijaQuery, hakemukset: Seq[HakijaHakemus], haku: Haku, hakukohdeOids: Seq[String]) = {
    val maksuvelvollisuudet: Set[String] = hakemukset.flatMap(_ match {
      case h: FullHakemus => h.preferenceEligibilities.filter(_.maksuvelvollisuus.isDefined).map(_.aoId)
      case h: AtaruHakemus => h.paymentObligations.filter(_._2 == "REQUIRED").keys
    }).toSet

    getLukuvuosimaksut(maksuvelvollisuudet, q.user.get.auditSession()).flatMap(lukuvuosimaksut => {
      kokoHaunTulosIfNoOppijanumero(q, haku.oid).flatMap { kokoHaunTulos =>
        val maksusByHakijaAndHakukohde = lukuvuosimaksut.groupBy(_.personOid).mapValues(_.toList.groupBy(_.hakukohdeOid))
        Future.sequence(hakemukset.flatMap(getKkHakijaV3(haku, q, kokoHaunTulos, hakukohdeOids, maksusByHakijaAndHakukohde)))
          .map(_.filter(_.hakemukset.nonEmpty))
      }
    })
  }


  private def kokoHaunTulosIfNoOppijanumero(q: KkHakijaQuery, hakuOid: String): Future[Option[SijoitteluTulos]] = q.oppijanumero match {
    case Some(_) => Future.successful(None)
    case None => getValintaTulos(ValintaTulosQuery(hakuOid, None)).map(Some(_))
  }

  private def getHakukelpoisuus(hakukohdeOid: String, kelpoisuudet: Seq[PreferenceEligibility]): PreferenceEligibility = {
    kelpoisuudet.find(_.aoId == hakukohdeOid) match {
      case Some(h) => h

      case None =>
        val defaultState = ""
        PreferenceEligibility(hakukohdeOid, defaultState, None, None)

    }
  }

  private def getKnownOrganizations(user: Option[User]): Set[String] = user.map(_.orgsFor("READ", "Hakukohde")).getOrElse(Set())

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

  private def getValintaTulos(q: ValintaTulosQuery): Future[SijoitteluTulos] = valintaTulos.?(q)(valintaTulosTimeout)
    .mapTo[SijoitteluTulos]

  private def getLukuvuosimaksut(hakukohdeOids: Set[String], auditSession: AuditSessionRequest): Future[Seq[Lukuvuosimaksu]] = {
    (valintaRekisteri ? LukuvuosimaksuQuery(hakukohdeOids, auditSession)).mapTo[Seq[Lukuvuosimaksu]]
  }

  private def getHakemukset(haku: Haku, hakemus: HakijaHakemus, lukuvuosimaksutByHakukohdeOid: Map[String, List[Lukuvuosimaksu]], q: KkHakijaQuery,
                            kokoHaunTulos: Option[SijoitteluTulos], hakukohdeOids: Seq[String]): Future[Seq[Hakemus]] = {
    val valintaTulosQuery = q.oppijanumero match {
      case Some(o) => ValintaTulosQuery(hakemus.applicationSystemId, Some(hakemus.oid))
      case None => ValintaTulosQuery(hakemus.applicationSystemId, None)
    }

    kokoHaunTulos.map(Future.successful)
      .getOrElse(getValintaTulos(valintaTulosQuery))
      .flatMap(tulos => Future.sequence(extractHakemukset(hakemus, lukuvuosimaksutByHakukohdeOid, q, haku, tulos, hakukohdeOids)).map(_.flatten))
  }

  private def extractHakemukset(hakemus: HakijaHakemus,
                                lukuvuosimaksutByHakukohdeOid: Map[String, List[Lukuvuosimaksu]],
                                q: KkHakijaQuery,
                                haku: Haku,
                                sijoitteluTulos: SijoitteluTulos,
                                hakukohdeOids: Seq[String]): Seq[Future[Option[Hakemus]]] = {
    (for {
      hakutoiveet: Seq[HakutoiveDTO] <- hakemus.hakutoiveet
    } yield hakutoiveet.map(toive =>
      if (toive.koulutusId.isDefined && queryMatches(q, toive, hakukohdeOids ++ q.hakukohde.toSeq))
        extractSingleHakemus(
          hakemus,
          lukuvuosimaksutByHakukohdeOid,
          q,
          toive,
          haku,
          sijoitteluTulos
        )
      else Future.successful(None)
    )).getOrElse(Seq.empty)
  }

  private def queryMatches(q: KkHakijaQuery, toive: HakutoiveDTO, hakukohdeOids: Seq[String]): Boolean = {
    (hakukohdeOids.isEmpty || toive.koulutusId.exists(hakukohdeOids.contains)) &&
      q.organisaatio.forall(toive.organizationParentOids.contains) &&
      toive.organizationParentOids.intersect(getKnownOrganizations(q.user)).nonEmpty
  }

  private def extractSingleHakemus(h: HakijaHakemus,
                                   lukuvuosimaksutByHakukohdeOid: Map[String, List[Lukuvuosimaksu]],
                                   q: KkHakijaQuery,
                                   toive: HakutoiveDTO,
                                   haku: Haku,
                                   sijoitteluTulos: SijoitteluTulos): Future[Option[Hakemus]] = h match {
    case hakemus: FullHakemus =>
      val hakemusOid = hakemus.oid
      val hakukohdeOid = toive.koulutusId.getOrElse("")
      val preferenceEligibilities = hakemus.preferenceEligibilities
      val hakukelpoisuus = getHakukelpoisuus(hakukohdeOid, preferenceEligibilities)
      for {
        hakukohteenkoulutukset: HakukohteenKoulutukset <- (tarjonta ? HakukohdeOid(hakukohdeOid)).mapTo[HakukohteenKoulutukset]
        kausi: String <- getKausi(haku.kausi, hakemusOid, koodisto)
        lasnaolot: Seq[Lasnaolo] <- getLasnaolot(sijoitteluTulos, hakukohdeOid, hakemusOid, hakukohteenkoulutukset.koulutukset)
      } yield {
        if (matchHakuehto(sijoitteluTulos, hakemusOid, hakukohdeOid)(q.hakuehto)) {
          for {
            answers <- hakemus.answers
          } yield Hakemus(
            haku = hakemus.applicationSystemId,
            hakuVuosi = haku.vuosi,
            hakuKausi = kausi,
            hakemusnumero = hakemusOid,
            organisaatio = toive.organizationOid.getOrElse(""),
            hakukohde = toive.koulutusId.getOrElse(""),
            hakukohdeKkId = hakukohteenkoulutukset.ulkoinenTunniste,
            avoinVayla = None, // TODO valinnoista?
            valinnanTila = sijoitteluTulos.valintatila(hakemusOid, hakukohdeOid),
            vastaanottotieto = sijoitteluTulos.vastaanottotila(hakemusOid, hakukohdeOid),
            ilmoittautumiset = lasnaolot,
            pohjakoulutus = getPohjakoulutukset(answers.koulutustausta.getOrElse(Koulutustausta())),
            julkaisulupa = answers.lisatiedot.getOrElse(Map()).get("lupaJulkaisu").map(_ == "true"),
            hKelpoisuus = hakukelpoisuus.status,
            hKelpoisuusLahde = hakukelpoisuus.source,
            hKelpoisuusMaksuvelvollisuus = hakukelpoisuus.maksuvelvollisuus,
            lukuvuosimaksu = resolveLukuvuosiMaksu(hakemus, hakukelpoisuus, lukuvuosimaksutByHakukohdeOid, hakukohdeOid),
            hakukohteenKoulutukset = hakukohteenkoulutukset.koulutukset
              .map(koulutus => koulutus.copy(koulutuksenAlkamiskausi = None, koulutuksenAlkamisvuosi = None, koulutuksenAlkamisPvms = None)),
            liitteet = attachmentToLiite(hakemus.attachmentRequests)
          )
        } else {
          None
        }
      }
    case hakemus: AtaruHakemus =>
      for {
        hakukohdeOid <- toive.koulutusId.fold[Future[String]](Future.failed(new RuntimeException("No hakukohde OID")))(Future.successful)
        hakukohteenkoulutukset: HakukohteenKoulutukset <- (tarjonta ? HakukohdeOid(hakukohdeOid)).mapTo[HakukohteenKoulutukset]
        kausi: String <- getKausi(haku.kausi, hakemus.oid, koodisto)
        lasnaolot: Seq[Lasnaolo] <- getLasnaolot(sijoitteluTulos, hakukohdeOid, hakemus.oid, hakukohteenkoulutukset.koulutukset)
      } yield {
        if (matchHakuehto(sijoitteluTulos, hakemus.oid, hakukohdeOid)(q.hakuehto)) {
          val hakukelpoisuus = PreferenceEligibility(hakukohdeOid, "", None, Some(hakemus.paymentObligations.getOrElse(hakukohdeOid, "NOT_CHECKED")))
          Some(Hakemus(
            haku = hakemus.applicationSystemId,
            hakuVuosi = haku.vuosi,
            hakuKausi = kausi,
            hakemusnumero = hakemus.oid,
            organisaatio = toive.organizationOid.getOrElse(""),
            hakukohde = toive.koulutusId.getOrElse(""),
            hakukohdeKkId = hakukohteenkoulutukset.ulkoinenTunniste,
            avoinVayla = None, // TODO valinnoista?
            valinnanTila = sijoitteluTulos.valintatila(hakemus.oid, hakukohdeOid),
            vastaanottotieto = sijoitteluTulos.vastaanottotila(hakemus.oid, hakukohdeOid),
            ilmoittautumiset = lasnaolot,
            pohjakoulutus = hakemus.kkPohjakoulutus,
            julkaisulupa = None,
            hKelpoisuus = hakukelpoisuus.status,
            hKelpoisuusLahde = hakukelpoisuus.source,
            hKelpoisuusMaksuvelvollisuus = hakukelpoisuus.maksuvelvollisuus,
            lukuvuosimaksu = resolveLukuvuosiMaksu(hakemus, hakukelpoisuus, lukuvuosimaksutByHakukohdeOid, hakukohdeOid),
            hakukohteenKoulutukset = hakukohteenkoulutukset.koulutukset
              .map(koulutus => koulutus.copy(koulutuksenAlkamiskausi = None, koulutuksenAlkamisvuosi = None, koulutuksenAlkamisPvms = None)),
            liitteet = None
          ))
        } else {
          None
        }
      }
  }

  private def resolveLukuvuosiMaksu(hakemus: HakijaHakemus,
                                    hakukelpoisuus: PreferenceEligibility,
                                    lukuvuosimaksutByHakukohdeOid: Map[String, List[Lukuvuosimaksu]],
                                    hakukohdeOid: String): Option[String] = {
    val hakukohteenMaksut = lukuvuosimaksutByHakukohdeOid.get(hakukohdeOid)
    if (hakukelpoisuus.maksuvelvollisuus.contains("REQUIRED") || hakukohteenMaksut.isDefined) {
      val maksuStatus = hakukohteenMaksut match {
        case None =>
          logger.info(
            s"Payment required for application yet no payment information found for application option "
              + s"$hakukohdeOid of application ${hakemus.oid}, defaulting to ${Maksuntila.maksamatta.toString}")
          Lukuvuosimaksu(hakemus.personOid.get, hakukohdeOid, Maksuntila.maksamatta, "System", Date.from(Instant.now()))
        case Some(ainoaMaksu :: Nil) => ainoaMaksu
        case Some(montaMaksua) if montaMaksua.size > 1 =>
          logger.warn(s"Found several lukuvuosimaksus for application option $hakukohdeOid of application ${hakemus.oid}, " +
            s"picking the first one: $montaMaksua")
          montaMaksua.head
      }
      Some(maksuStatus.maksuntila.toString)
    } else {
      None
    }
  }

  def attachmentToLiite(attachments: Seq[HakemusAttachmentRequest]): Option[Seq[Liite]] = {
    var liitteet: Seq[Liite] = attachments.map(a => Liite(a.preferenceAoId.getOrElse(""), a.preferenceAoGroupId.getOrElse(""),
      a.receptionStatus, a.processingStatus, getLiitteenNimi(a.applicationAttachment), a.applicationAttachment.address.recipient))
    liitteet match {
      case Seq() => None
      case _ => Some(liitteet)
    }
  }

  def getOsaaminenOsaalue(hakemusAnswers: Option[HakemusAnswers], key: String): String = {
    hakemusAnswers match {
      case Some(ha) => ha.osaaminen match {
        case Some(a) => a.getOrElse(key, "")
        case None => ""
      }
      case None => ""
    }
  }

  def getLiitteenNimi(liite: ApplicationAttachment): String = {
    (liite.name, liite.header) match {
      case (Some(a),_) => a.translations.fi
      case (_,Some(b)) => b.translations.fi
      case (None, None) => ""
    }
  }

  private def getKkHakijaV1(haku: Haku, q: KkHakijaQuery, kokoHaunTulos: Option[SijoitteluTulos], hakukohdeOids: Seq[String])(h: HakijaHakemus): Option[Future[Hakija]] = h match {
    case hakemus: FullHakemus =>
      for {
        answers: HakemusAnswers <- hakemus.answers
        henkilotiedot: HakemusHenkilotiedot <- answers.henkilotiedot
        henkiloOid <- hakemus.personOid
      } yield for {
        hakemukset <- getHakemukset(haku, hakemus, Map(), q, kokoHaunTulos, hakukohdeOids)
        maa <- getMaakoodi(henkilotiedot.asuinmaa.getOrElse(""), koodisto)
        toimipaikka <- getToimipaikka(maa, henkilotiedot.Postinumero, henkilotiedot.kaupunkiUlkomaa, koodisto)
        suoritukset <- (suoritukset ? SuoritysTyyppiQuery(henkilo = henkiloOid, komo = YoTutkinto.yotutkinto)).mapTo[Seq[VirallinenSuoritus]]
        kansalaisuus <- getMaakoodi(henkilotiedot.kansalaisuus.getOrElse(""), koodisto)
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
        kansalaisuus = Some(kansalaisuus),
        kaksoiskansalaisuus = None,
        kansalaisuudet = None,
        syntymaaika = None,
        matkapuhelin = henkilotiedot.matkapuhelinnumero1.flatMap(_.blankOption),
        puhelin = henkilotiedot.matkapuhelinnumero2.flatMap(_.blankOption),
        sahkoposti = henkilotiedot.Sähköposti.flatMap(_.blankOption),
        kotikunta = henkilotiedot.kotikunta.flatMap(_.blankOption).getOrElse("999"),
        sukupuoli = henkilotiedot.sukupuoli.getOrElse(""),
        aidinkieli = henkilotiedot.aidinkieli.flatMap(_.blankOption).getOrElse("99"),
        asiointikieli = getAsiointikieli(answers.lisatiedot.getOrElse(Map()).get("asiointikieli").getOrElse("9")),
        koulusivistyskieli = henkilotiedot.koulusivistyskieli.flatMap(_.blankOption).getOrElse("99"),
        koulutusmarkkinointilupa = answers.lisatiedot.getOrElse(Map()).get("lupaMarkkinointi").map(_ == "true"),
        onYlioppilas = isYlioppilas(suoritukset),
        yoSuoritusVuosi = None,
        turvakielto = henkilotiedot.turvakielto.contains("true"),
        hakemukset = hakemukset.map(hakemus => hakemus.copy(liitteet = None, julkaisulupa = hakemus.julkaisulupa, hKelpoisuusMaksuvelvollisuus = None))
      )
    case hakemus: AtaruHakemus =>
      Some(for {
        hakemukset <- getHakemukset(haku, hakemus, Map(), q, kokoHaunTulos, hakukohdeOids)
        suoritukset <- (suoritukset ? SuoritysTyyppiQuery(henkilo = hakemus.henkilo.oidHenkilo, komo = YoTutkinto.yotutkinto)).mapTo[Seq[VirallinenSuoritus]]
      } yield {
        val syntymaaika = hakemus.henkilo.syntymaaika.map(s => new SimpleDateFormat("dd.MM.yyyy").format(new SimpleDateFormat("yyyy-MM-dd").parse(s)))
        Hakija(
          hetu = getHetu(hakemus.henkilo.hetu, syntymaaika, hakemus.oid),
          oppijanumero = hakemus.henkilo.oidHenkilo,
          sukunimi = hakemus.henkilo.sukunimi.getOrElse(""),
          etunimet = hakemus.henkilo.etunimet.getOrElse(""),
          kutsumanimi = hakemus.henkilo.kutsumanimi.getOrElse(""),
          lahiosoite = hakemus.lahiosoite,
          postinumero = hakemus.postinumero,
          postitoimipaikka = hakemus.postitoimipaikka.getOrElse(""),
          maa = hakemus.asuinmaa,
          kansalaisuus = Some(hakemus.henkilo.kansalaisuus.headOption.map(_.kansalaisuusKoodi).getOrElse("999")),
          kaksoiskansalaisuus = None,
          kansalaisuudet = None,
          syntymaaika = syntymaaika,
          matkapuhelin = Some(hakemus.matkapuhelin),
          puhelin = None,
          sahkoposti = Some(hakemus.email),
          kotikunta = hakemus.kotikunta.getOrElse("999"),
          sukupuoli = hakemus.henkilo.sukupuoli.getOrElse(""),
          aidinkieli = hakemus.henkilo.aidinkieli.map(_.kieliKoodi.toUpperCase).getOrElse("99"),
          asiointikieli = hakemus.henkilo.asiointiKieli.map(_.kieliKoodi) match {
            case Some("fi") => "1"
            case Some("sv") => "2"
            case Some("en") => "3"
            case _ => "9"
          },
          koulusivistyskieli = "99",
          koulutusmarkkinointilupa = None,
          onYlioppilas = isYlioppilas(suoritukset),
          yoSuoritusVuosi = None,
          turvakielto = hakemus.henkilo.turvakielto.getOrElse(false),
          hakemukset = hakemukset
        )
      })
  }


  private def getKkHakijaV3(haku: Haku, q: KkHakijaQuery, kokoHaunTulos: Option[SijoitteluTulos], hakukohdeOids: Seq[String],
                            lukuvuosiMaksutByHenkiloAndHakukohde: Map[String, Map[String, List[Lukuvuosimaksu]]])(h: HakijaHakemus): Option[Future[Hakija]] = h match {
    case hakemus: FullHakemus =>
      for {
        answers: HakemusAnswers <- hakemus.answers
        henkilotiedot: HakemusHenkilotiedot <- answers.henkilotiedot
        henkiloOid <- hakemus.personOid
      } yield for {
        hakemukset <- getHakemukset(haku, hakemus, lukuvuosiMaksutByHenkiloAndHakukohde.getOrElse(henkiloOid, Map()), q, kokoHaunTulos, hakukohdeOids)
        maa <- getMaakoodi(henkilotiedot.asuinmaa.getOrElse(""), koodisto)
        toimipaikka <- getToimipaikka(maa, henkilotiedot.Postinumero, henkilotiedot.kaupunkiUlkomaa, koodisto)
        suoritukset <- (suoritukset ? SuoritysTyyppiQuery(henkilo = henkiloOid, komo = YoTutkinto.yotutkinto)).mapTo[Seq[VirallinenSuoritus]]
        kansalaisuus <- getMaakoodi(henkilotiedot.kansalaisuus.getOrElse(""), koodisto)
        kaksoiskansalaisuus <- getMaakoodi(henkilotiedot.kaksoiskansalaisuus.getOrElse(""), koodisto)
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
        kansalaisuus = None,
        kaksoiskansalaisuus = None,
        kansalaisuudet = if (henkilotiedot.kaksoiskansalaisuus.isDefined && henkilotiedot.kaksoiskansalaisuus.get.nonEmpty) Some(List(kansalaisuus, kaksoiskansalaisuus)) else Some(List(kansalaisuus)),
        syntymaaika = henkilotiedot.syntymaaika,
        matkapuhelin = henkilotiedot.matkapuhelinnumero1.flatMap(_.blankOption),
        puhelin = henkilotiedot.matkapuhelinnumero2.flatMap(_.blankOption),
        sahkoposti = henkilotiedot.Sähköposti.flatMap(_.blankOption),
        kotikunta = henkilotiedot.kotikunta.flatMap(_.blankOption).getOrElse("999"),
        sukupuoli = henkilotiedot.sukupuoli.getOrElse(""),
        aidinkieli = henkilotiedot.aidinkieli.flatMap(_.blankOption).getOrElse("99"),
        asiointikieli = getAsiointikieli(answers.lisatiedot.getOrElse(Map()).get("asiointikieli").getOrElse("9")),
        koulusivistyskieli = henkilotiedot.koulusivistyskieli.flatMap(_.blankOption).getOrElse("99"),
        koulutusmarkkinointilupa = answers.lisatiedot.getOrElse(Map()).get("lupaMarkkinointi").map(_ == "true"),
        onYlioppilas = isYlioppilas(suoritukset),
        yoSuoritusVuosi = getYoSuoritusVuosi(suoritukset),
        turvakielto = henkilotiedot.turvakielto.contains("true"),
        hakemukset = hakemukset
      )
    case hakemus: AtaruHakemus =>
      Some(for {
        hakemukset <- getHakemukset(haku, hakemus, lukuvuosiMaksutByHenkiloAndHakukohde.getOrElse(hakemus.henkilo.oidHenkilo, Map()), q, kokoHaunTulos, hakukohdeOids)
        suoritukset <- (suoritukset ? SuoritysTyyppiQuery(henkilo = hakemus.henkilo.oidHenkilo, komo = YoTutkinto.yotutkinto)).mapTo[Seq[VirallinenSuoritus]]
      } yield {
        val syntymaaika = hakemus.henkilo.syntymaaika.map(s => new SimpleDateFormat("dd.MM.yyyy").format(new SimpleDateFormat("yyyy-MM-dd").parse(s)))
        Hakija(
          hetu = getHetu(hakemus.henkilo.hetu, syntymaaika, hakemus.oid),
          oppijanumero = hakemus.henkilo.oidHenkilo,
          sukunimi = hakemus.henkilo.sukunimi.getOrElse(""),
          etunimet = hakemus.henkilo.etunimet.getOrElse(""),
          kutsumanimi = hakemus.henkilo.kutsumanimi.getOrElse(""),
          lahiosoite = hakemus.lahiosoite,
          postinumero = hakemus.postinumero,
          postitoimipaikka = hakemus.postitoimipaikka.getOrElse(""),
          maa = hakemus.asuinmaa,
          kansalaisuus = None,
          kaksoiskansalaisuus = None,
          kansalaisuudet= Some(hakemus.henkilo.kansalaisuus.map(_.kansalaisuusKoodi)),
          syntymaaika = syntymaaika,
          matkapuhelin = Some(hakemus.matkapuhelin),
          puhelin = None,
          sahkoposti = Some(hakemus.email),
          kotikunta = hakemus.kotikunta.getOrElse("999"),
          sukupuoli = hakemus.henkilo.sukupuoli.getOrElse(""),
          aidinkieli = hakemus.henkilo.aidinkieli.map(_.kieliKoodi.toUpperCase).getOrElse("99"),
          asiointikieli = hakemus.henkilo.asiointiKieli.map(_.kieliKoodi) match {
            case Some("fi") => "1"
            case Some("sv") => "2"
            case Some("en") => "3"
            case _ => "9"
          },
          koulusivistyskieli = "99",
          koulutusmarkkinointilupa = None,
          onYlioppilas = isYlioppilas(suoritukset),
          yoSuoritusVuosi = getYoSuoritusVuosi(suoritukset),
          turvakielto = hakemus.henkilo.turvakielto.getOrElse(false),
          hakemukset = hakemukset
        )
      })
  }

  private def getKkHakijaV2(haku: Haku, q: KkHakijaQuery, kokoHaunTulos: Option[SijoitteluTulos], hakukohdeOids: Seq[String],
                            lukuvuosiMaksutByHenkiloAndHakukohde: Map[String, Map[String, List[Lukuvuosimaksu]]])(h: HakijaHakemus): Option[Future[Hakija]] = h match {
    case hakemus: FullHakemus =>
      for {
        answers: HakemusAnswers <- hakemus.answers
        henkilotiedot: HakemusHenkilotiedot <- answers.henkilotiedot
        henkiloOid <- hakemus.personOid
      } yield for {
        hakemukset <- getHakemukset(haku, hakemus, lukuvuosiMaksutByHenkiloAndHakukohde.getOrElse(henkiloOid, Map()), q, kokoHaunTulos, hakukohdeOids)
        maa <- getMaakoodi(henkilotiedot.asuinmaa.getOrElse(""), koodisto)
        toimipaikka <- getToimipaikka(maa, henkilotiedot.Postinumero, henkilotiedot.kaupunkiUlkomaa, koodisto)
        suoritukset <- (suoritukset ? SuoritysTyyppiQuery(henkilo = henkiloOid, komo = YoTutkinto.yotutkinto)).mapTo[Seq[VirallinenSuoritus]]
        kansalaisuus <- getMaakoodi(henkilotiedot.kansalaisuus.getOrElse(""), koodisto)
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
        kansalaisuus = Some(kansalaisuus),
        kaksoiskansalaisuus = henkilotiedot.kaksoiskansalaisuus,
        kansalaisuudet = None,
        syntymaaika = henkilotiedot.syntymaaika,
        matkapuhelin = henkilotiedot.matkapuhelinnumero1.flatMap(_.blankOption),
        puhelin = henkilotiedot.matkapuhelinnumero2.flatMap(_.blankOption),
        sahkoposti = henkilotiedot.Sähköposti.flatMap(_.blankOption),
        kotikunta = henkilotiedot.kotikunta.flatMap(_.blankOption).getOrElse("999"),
        sukupuoli = henkilotiedot.sukupuoli.getOrElse(""),
        aidinkieli = henkilotiedot.aidinkieli.flatMap(_.blankOption).getOrElse("99"),
        asiointikieli = getAsiointikieli(answers.lisatiedot.getOrElse(Map()).get("asiointikieli").getOrElse("9")),
        koulusivistyskieli = henkilotiedot.koulusivistyskieli.flatMap(_.blankOption).getOrElse("99"),
        koulutusmarkkinointilupa = answers.lisatiedot.getOrElse(Map()).get("lupaMarkkinointi").map(_ == "true"),
        onYlioppilas = isYlioppilas(suoritukset),
        yoSuoritusVuosi = getYoSuoritusVuosi(suoritukset),
        turvakielto = henkilotiedot.turvakielto.contains("true"),
        hakemukset = hakemukset
      )
    case hakemus: AtaruHakemus =>
      Some(for {
        hakemukset <- getHakemukset(haku, hakemus, lukuvuosiMaksutByHenkiloAndHakukohde.getOrElse(hakemus.henkilo.oidHenkilo, Map()), q, kokoHaunTulos, hakukohdeOids)
        suoritukset <- (suoritukset ? SuoritysTyyppiQuery(henkilo = hakemus.henkilo.oidHenkilo, komo = YoTutkinto.yotutkinto)).mapTo[Seq[VirallinenSuoritus]]
      } yield {
        val syntymaaika = hakemus.henkilo.syntymaaika.map(s => new SimpleDateFormat("dd.MM.yyyy").format(new SimpleDateFormat("yyyy-MM-dd").parse(s)))
        Hakija(
          hetu = getHetu(hakemus.henkilo.hetu, syntymaaika, hakemus.oid),
          oppijanumero = hakemus.henkilo.oidHenkilo,
          sukunimi = hakemus.henkilo.sukunimi.getOrElse(""),
          etunimet = hakemus.henkilo.etunimet.getOrElse(""),
          kutsumanimi = hakemus.henkilo.kutsumanimi.getOrElse(""),
          lahiosoite = hakemus.lahiosoite,
          postinumero = hakemus.postinumero,
          postitoimipaikka = hakemus.postitoimipaikka.getOrElse(""),
          maa = hakemus.asuinmaa,
          kansalaisuus = Some(hakemus.henkilo.kansalaisuus.headOption.map(_.kansalaisuusKoodi).getOrElse("999")),
          kaksoiskansalaisuus = None,
          kansalaisuudet= None,
          syntymaaika = syntymaaika,
          matkapuhelin = Some(hakemus.matkapuhelin),
          puhelin = None,
          sahkoposti = Some(hakemus.email),
          kotikunta = hakemus.kotikunta.getOrElse("999"),
          sukupuoli = hakemus.henkilo.sukupuoli.getOrElse(""),
          aidinkieli = hakemus.henkilo.aidinkieli.map(_.kieliKoodi.toUpperCase).getOrElse("99"),
          asiointikieli = hakemus.henkilo.asiointiKieli.map(_.kieliKoodi) match {
            case Some("fi") => "1"
            case Some("sv") => "2"
            case Some("en") => "3"
            case _ => "9"
          },
          koulusivistyskieli = "99",
          koulutusmarkkinointilupa = None,
          onYlioppilas = isYlioppilas(suoritukset),
          yoSuoritusVuosi = getYoSuoritusVuosi(suoritukset),
          turvakielto = hakemus.henkilo.turvakielto.getOrElse(false),
          hakemukset = hakemukset
        )
      })
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
    case "suomi" => "1"
    case "ruotsi" => "2"
    case "englanti" => "3"
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

  def getYoSuoritusVuosi(suoritukset: Seq[VirallinenSuoritus]): Option[String] = {
    suoritukset.find(p => p.tila == "VALMIS" && p.vahvistettu) match {
      case Some(m) => Some(m.valmistuminen.getYear().toString())
      case None => None
    }
  }

  private val logger = LoggerFactory.getLogger(getClass)

  def getMaakoodi(koodiArvo: String, koodisto: ActorRef)(implicit timeout: Timeout, ec: ExecutionContext): Future[String] = koodiArvo.toLowerCase match {
    case "fin" => Future.successful("246")
    case "" => Future.successful("999")

    case arvo =>
      val maaFuture = (koodisto ? GetRinnasteinenKoodiArvoQuery("maatjavaltiot1", arvo, "maatjavaltiot2")).mapTo[String]
      maaFuture.onFailure {
        case e => logger.error(s"failed to fetch country $koodiArvo")
      }
      maaFuture
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
