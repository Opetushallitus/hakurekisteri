package fi.vm.sade.hakurekisteri.integration.hakemus

import akka.actor.ActorRef
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.Oids
import fi.vm.sade.hakurekisteri.hakija._
import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient
import fi.vm.sade.hakurekisteri.integration.hakemus.dto.SuoritusJaArvosanat
import fi.vm.sade.hakurekisteri.integration.haku.{GetHaku, Haku}
import fi.vm.sade.hakurekisteri.integration.kooste.IKoosteService
import fi.vm.sade.hakurekisteri.opiskelija.Opiskelija
import fi.vm.sade.hakurekisteri.rest.support.{Kausi, Resource}
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.suoritus.{Komoto, Suoritus, VirallinenSuoritus, yksilollistaminen}
import org.joda.time.{DateTime, LocalDate, MonthDay}

import scala.collection.immutable.Iterable
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.collection.JavaConverters._

trait Hakupalvelu {
  def getHakijat(q: HakijaQuery): Future[Seq[Hakija]]
  def getHakukohdeOids(hakukohderyhma: String, haku: String): Future[Seq[String]]
}

case class ThemeQuestion(`type`: String, messageText: String, applicationOptionOids: Seq[String], options: Option[Map[String, String]],
                         isHaunLisakysymys: Boolean = false)

case class HakukohdeResult(hakukohteenNimiUri: String)

case class HakukohdeResultContainer(result: HakukohdeResult)

case class HakukohdeSearchResult(oid: String)

case class HakukohdeSearchResultTarjoaja(tulokset: Seq[HakukohdeSearchResult])

case class HakukohdeSearchResultList(tulokset: Seq[HakukohdeSearchResultTarjoaja])

case class HakukohdeSearchResultContainer(result: HakukohdeSearchResultList)

class AkkaHakupalvelu(virkailijaClient: VirkailijaRestClient, hakemusService: IHakemusService, koosteService: IKoosteService, hakuActor: ActorRef)(implicit val ec: ExecutionContext)
  extends Hakupalvelu {
  val Pattern = "preference(\\d+).*".r

  private val acceptedResponseCode: Int = 200

  private val maxRetries: Int = 2

  private def restRequest[A <: AnyRef](uri: String, args: AnyRef*)(implicit mf: Manifest[A]): Future[A] =
    virkailijaClient.readObject[A](uri, args: _*)(acceptedResponseCode, maxRetries)

  private def getLisakysymyksetForHaku(hakuOid: String): Future[Map[String, ThemeQuestion]] = {
    restRequest[Map[String, ThemeQuestion]]("haku-app.themequestions", hakuOid).map(_ ++ AkkaHakupalvelu.hardCodedLisakysymys)
  }

  override def getHakukohdeOids(hakukohderyhma: String, haku: String): Future[Seq[String]] = {
    restRequest[HakukohdeSearchResultContainer]("tarjonta-service.hakukohde.search", Map("hakuOid" -> haku, "organisaatioRyhmaOid"->hakukohderyhma))
      .map(_.result.tulokset.flatMap(tarjoaja => tarjoaja.tulokset.map(hakukohde => hakukohde.oid)))
  }

  private def hakukohdeOids(organisaatio: Option[String],
                            hakuOid: Option[String],
                            hakukohdekoodi: Option[String]): Future[Seq[String]] = {
    val hakukohteenNimiUriHakuehto = hakukohdekoodi.map(koodi => "hakukohteenNimiUri" -> koodi)
    val q = (organisaatio.map("organisationOid" -> _) ++ hakuOid.map("hakuOid" -> _) ++ hakukohteenNimiUriHakuehto).toMap
    restRequest[HakukohdeSearchResultContainer]("tarjonta-service.hakukohde.search", q)
      .map(_.result.tulokset.flatMap(tarjoaja => tarjoaja.tulokset.map(hakukohde => hakukohde.oid)))
  }

  override def getHakijat(q: HakijaQuery): Future[Seq[Hakija]] = {
    import akka.pattern._
    implicit val timeout: Timeout = 120.seconds

    def hakuAndLisakysymykset(hakuOid: String): Future[(Haku, Map[String, ThemeQuestion])] = {
      val hakuF = (hakuActor ? GetHaku(hakuOid)).mapTo[Haku]
      val lisakysymyksetF = getLisakysymyksetForHaku(hakuOid)
      for (haku <- hakuF; lisakysymykset <- lisakysymyksetF) yield (haku, lisakysymykset)
    }

    q match {
      case HakijaQuery(Some(hakuOid), organisaatio, None, _, _, _) =>
        for {
          (haku, lisakysymykset) <- hakuAndLisakysymykset(hakuOid)
          hakemukset <- hakemusService.hakemuksetForHaku(hakuOid, organisaatio).map(_.filter(_.stateValid))
          //hakijaSuorituksetMap <- koosteService.getSuoritukset(hakuOid, hakemukset) // TODO
        } yield hakemukset
          .map { hakemus =>
              //val koosteData: Option[Map[String, String]] = hakijaSuorituksetMap.get(hakemus.personOid.get)
              AkkaHakupalvelu.getHakija(hakemus, haku, lisakysymykset, None, List()) // TODO
          }
      case HakijaQuery(hakuOid, organisaatio, hakukohdekoodi, _, _, _) =>
        for {
          hakukohdeOids <- hakukohdeOids(organisaatio, hakuOid, hakukohdekoodi)
          hakukohteittain <- Future.sequence(hakukohdeOids.map(hakemusService.hakemuksetForHakukohde(_, organisaatio)))
          hauittain = hakukohdeOids.zip(hakukohteittain).groupBy(_._2.headOption.map(_.applicationSystemId))
          hakijat <- Future.sequence(for {
            (hakuOid, hakukohteet) <- hauittain if hakuOid.isDefined // when would it not be defined?
            hakuJaLisakysymykset = hakuAndLisakysymykset(hakuOid.get)
            (hakukohdeOid, hakemukset) <- hakukohteet
            suorituksetByOppija = koosteService.getSuoritukset(hakuOid.get, hakemukset.filter(_.stateValid))
            hakemus <- hakemukset if hakemus.stateValid
          } yield for {
            hakijaSuorituksetMap <- suorituksetByOppija
            (haku, lisakysymykset) <- hakuJaLisakysymykset
          } yield {
            // val koosteData: Option[Map[String, String]] = hakijaSuorituksetMap.get(hakemus.personOid.get)
            AkkaHakupalvelu.getHakija(hakemus, haku, lisakysymykset, hakukohdekoodi.map(_ => hakukohdeOid), List()) // TODO
          })
        } yield hakijat.toSeq
    }
  }
}

object AkkaHakupalvelu {
  val DEFAULT_POHJA_KOULUTUS: String = "1"

  val hardCodedLisakysymys: Map[String, ThemeQuestion] = Map(

    "hojks" -> ThemeQuestion(
      isHaunLisakysymys = true,
      `type` = "ThemeRadioButtonQuestion",
      messageText = "Onko sinulle laadittu peruskoulussa tai muita opintoja suorittaessasi HOJKS (Henkilökohtainen opetuksen järjestämistä koskeva " +
        "suunnitelma)?",
      applicationOptionOids = Nil,
      options = Some(Map("true" -> "Kyllä", "false" -> "Ei"))),

    "koulutuskokeilu" -> ThemeQuestion(
      isHaunLisakysymys = true,
      `type` = "ThemeRadioButtonQuestion",
      messageText = "Oletko ollut koulutuskokeilussa?",
      applicationOptionOids = Nil,
      options = Some(Map("true" -> "Kyllä", "false" -> "Ei"))),

    "miksi_ammatilliseen" -> ThemeQuestion(
      isHaunLisakysymys = true,
      `type` = "ThemeTextQuestion",
      messageText = "Miksi haet erityisoppilaitokseen?",
      applicationOptionOids = Nil,
      options = None),

    "TYOKOKEMUSKUUKAUDET" -> ThemeQuestion(
      isHaunLisakysymys = true,
      `type` = "ThemeTextQuestion",
      messageText = "Työkokemus kuukausina",
      applicationOptionOids = Nil,
      options = None),

    "lupaSahkoisesti" -> ThemeQuestion(
      isHaunLisakysymys = true,
      `type` = "ThemeRadioButtonQuestion",
      messageText = "Opiskelijavalinnan tulokset saa lähettää minulle myös sähköisesti",
      applicationOptionOids = Nil,
      options = Some(Map("true" -> "Kyllä", "false" -> "Ei"))),

    "lupaSms" -> ThemeQuestion(
      isHaunLisakysymys = true,
      `type` = "ThemeRadioButtonQuestion",
      messageText = "Minulle saa lähettää tietoa opiskelijavalinnan etenemisestä ja tuloksista myös tekstiviestillä.",
      applicationOptionOids = Nil,
      options = Some(Map("true" -> "Kyllä", "false" -> "Ei")))
  )


  def getVuosi(vastaukset: Koulutustausta)(pohjakoulutus: String): Option[String] = pohjakoulutus match {
    case "9" => vastaukset.lukioPaattotodistusVuosi
    case "7" => Some((LocalDate.now.getYear + 1).toString)
    case _ => vastaukset.PK_PAATTOTODISTUSVUOSI
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
      "LISAKOULUTUS_MAAHANMUUTTO_LUKIO" -> tausta.LISAKOULUTUS_MAAHANMUUTTO_LUKIO
    ).mapValues(checkKoulutus).filter { case (_, done) => done }.keys
  }

  def attachmentRequestToLiite(har: HakemusAttachmentRequest): Liite = {
    val name = (har.applicationAttachment.name, har.applicationAttachment.header) match {
      case (Some(name), _) => name.translations.fi
      case (_, Some(header)) => header.translations.fi
      case (None, None) => ""
    }
    var prefAoId = (har.preferenceAoId) match { case Some(a) => a case None => ""}
    var prefAoGroupId = (har.preferenceAoGroupId) match { case Some(a) => a case None => ""}
    Liite(prefAoId, prefAoGroupId, har.receptionStatus, har.processingStatus, name, har.applicationAttachment.address.recipient)
  }

  def getLiitteet(hakemus: FullHakemus): Seq[Liite] = {
    hakemus.attachmentRequests.map(a=> attachmentRequestToLiite(a))
  }

  def getLisakysymykset(hakemus: FullHakemus, lisakysymykset: Map[String, ThemeQuestion], hakukohdeOid: Option[String]): Seq[Lisakysymys] = {

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
      val kysymys: ThemeQuestion = lisakysymykset.getOrElse(kysymysId, hardCodedLisakysymys(kysymysId))
      Lisakysymys(
        kysymysid = kysymysId,
        hakukohdeOids = kysymys.applicationOptionOids,
        kysymystyyppi = kysymys.`type`,
        kysymysteksti = kysymys.messageText,
        vastaukset = getAnswersByType(kysymys, arvo, compositeIds.answerId)
      )
    }

    def getAnswersByType(tq: ThemeQuestion, vastaus: String, vastausId: Option[String]): Seq[LisakysymysVastaus] = {
      tq.`type` match {
        case "ThemeRadioButtonQuestion" => Seq(LisakysymysVastaus(
          vastausid = Some(vastaus),
          vastausteksti = tq.options.get.apply(if (vastaus.isEmpty) "false" else vastaus)
        ))
        case "ThemeCheckBoxQuestion" =>
          if (vastaus != "true") {
            Seq()
          }
          else {
            Seq(LisakysymysVastaus(
              vastausid = vastausId,
              vastausteksti = tq.options.get(vastausId.get)
            ))
          }
        case _ => Seq(LisakysymysVastaus(
          vastausid = None,
          vastausteksti = vastaus
        ))
      }
    }

    def thatAreLisakysymysInHakukohde(kysymysId: String): Boolean = {
      lisakysymykset.keys.exists {
        (key: String) =>
          kysymysId.contains(key) && {
            val noHakukohdeSpecified = hakukohdeOid.isEmpty
            val isDefaultKysymys = lisakysymykset(key).isHaunLisakysymys
            val isKysymysForSpecifiedHakukohde = !noHakukohdeSpecified && lisakysymykset(key).applicationOptionOids.contains(hakukohdeOid.get)
            val kysymysIncludedForCurrentHakukohde = isDefaultKysymys || isKysymysForSpecifiedHakukohde

            noHakukohdeSpecified || kysymysIncludedForCurrentHakukohde
          }
      }
    }

    val answers: HakemusAnswers = hakemus.answers.getOrElse(HakemusAnswers())
    val flatAnswers = answers.hakutoiveet.getOrElse(Map()) ++ answers.osaaminen.getOrElse(Map()) ++ answers.lisatiedot.getOrElse(Map())

    val missingQuestionAnswers = hardCodedLisakysymys.filterNot(q => {
      val id = q._1
      flatAnswers.contains(id)
    }).map(q => (q._1, ""))

    /*
    The missing question answers are added here forcefully since the excel sheets and what not need to have them in all cases
     */
    val filteredAnswers = flatAnswers.filterKeys(thatAreLisakysymysInHakukohde) ++ missingQuestionAnswers
    val finalanswers = filteredAnswers
      .map { case (avain, arvo) => extractLisakysymysFromAnswer(avain, arvo) }
      .groupBy(_.kysymysid)
      .map { case (questionId, answerList) => mergeAnswers(questionId, answerList)}
      .toSeq

    finalanswers
  }

  def getOsaaminenOsaalue(hakemusAnswers: Option[HakemusAnswers], key: String): Option[String] = {
    hakemusAnswers match {
      case Some(ha) => ha.osaaminen match { case Some(a) => a.get(key) case None => None }
      case None => None
    }
  }

  def getHakija(hakemus: FullHakemus, haku: Haku, lisakysymykset: Map[String, ThemeQuestion], hakukohdeOid: Option[String], suoritusJaArvosanat: List[SuoritusJaArvosanat]): Hakija = {
    val kesa = new MonthDay(6, 4)
    implicit val v = hakemus.answers
    val koulutustausta = for (a: HakemusAnswers <- v; k: Koulutustausta <- a.koulutustausta) yield k
    val lahtokoulu: Option[String] = for (k <- koulutustausta; l <- k.lahtokoulu) yield l
    val pohjakoulutus = Option(PohjakoulutusUtil.pohjakoulutus(haku.vuosi, haku.kohdejoukkoUri.get,
      com.google.common.base.Optional.of(hakemus.answers.get.koulutustausta.get.POHJAKOULUTUS.get),
      hakemus.personOid.getOrElse(""), suoritusJaArvosanat.asJava).get())
    val todistusVuosi: Option[String] = for (p: String <- pohjakoulutus; k <- koulutustausta; v <- getVuosi(k)(p)) yield v
    val kieli = (for (a <- v; henkilotiedot: HakemusHenkilotiedot <- a.henkilotiedot; aidinkieli <- henkilotiedot.aidinkieli) yield aidinkieli).getOrElse("FI")
    val myontaja = lahtokoulu.getOrElse("")
    val suorittaja = hakemus.personOid.getOrElse("")
    val valmistuminen = todistusVuosi.flatMap(vuosi => Try(kesa.toLocalDate(vuosi.toInt)).toOption).getOrElse(new LocalDate(0))
    val julkaisulupa = (for (
      a <- v;
      lisatiedot <- a.lisatiedot;
      julkaisu <- lisatiedot.get("lupaJulkaisu")
    ) yield julkaisu).getOrElse("false").toBoolean

    val yleinen_kielitutkinto_fi = getOsaaminenOsaalue(hakemus.answers, "yleinen_kielitutkinto_fi")
    val valtionhallinnon_kielitutkinto_fi = getOsaaminenOsaalue(hakemus.answers, "valtionhallinnon_kielitutkinto_fi")
    val yleinen_kielitutkinto_sv = getOsaaminenOsaalue(hakemus.answers, "yleinen_kielitutkinto_sv")
    val valtionhallinnon_kielitutkinto_sv = getOsaaminenOsaalue(hakemus.answers, "valtionhallinnon_kielitutkinto_sv")
    val yleinen_kielitutkinto_en = getOsaaminenOsaalue(hakemus.answers, "yleinen_kielitutkinto_en")
    val valtionhallinnon_kielitutkinto_en = getOsaaminenOsaalue(hakemus.answers, "valtionhallinnon_kielitutkinto_en")
    val yleinen_kielitutkinto_se = getOsaaminenOsaalue(hakemus.answers, "yleinen_kielitutkinto_se")
    val valtionhallinnon_kielitutkinto_se = getOsaaminenOsaalue(hakemus.answers, "valtionhallinnon_kielitutkinto_se")

    val osaaminen = Osaaminen(yleinen_kielitutkinto_fi, valtionhallinnon_kielitutkinto_fi,
                          yleinen_kielitutkinto_sv, valtionhallinnon_kielitutkinto_sv,
                          yleinen_kielitutkinto_en, valtionhallinnon_kielitutkinto_en,
                          yleinen_kielitutkinto_se, valtionhallinnon_kielitutkinto_se)

    val lisapistekoulutus = for (
      tausta <- koulutustausta;
      lisatausta <- kaydytLisapisteKoulutukset(tausta).headOption
    ) yield lisatausta
    val henkilotiedot: Option[HakemusHenkilotiedot] = for (a <- v; henkilotiedot <- a.henkilotiedot) yield henkilotiedot
    def getHenkiloTietoOrElse(f: (HakemusHenkilotiedot) => Option[String], orElse: String) =
      (for (h <- henkilotiedot; osoite <- f(h)) yield osoite).getOrElse(orElse)

    def getHenkiloTietoOrBlank(f: (HakemusHenkilotiedot) => Option[String]): String = getHenkiloTietoOrElse(f, "")

    Hakija(
      Henkilo(
        lahiosoite = getHenkiloTietoOrElse(_.lahiosoite, getHenkiloTietoOrBlank(_.osoiteUlkomaa)),
        postinumero = getHenkiloTietoOrElse(_.Postinumero, "00000"),
        postitoimipaikka = getHenkiloTietoOrElse(_.Postitoimipaikka, getHenkiloTietoOrBlank(_.kaupunkiUlkomaa)),
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
        kansalaisuus = getHenkiloTietoOrElse(_.kansalaisuus, "FIN"),
        kaksoiskansalaisuus = getHenkiloTietoOrBlank(_.kaksoiskansalaisuus),
        asiointiKieli = kieli,
        eiSuomalaistaHetua = getHenkiloTietoOrElse(_.onkoSinullaSuomalainenHetu, "false").toBoolean,
        sukupuoli = getHenkiloTietoOrBlank(_.sukupuoli),
        hetu = getHenkiloTietoOrBlank(_.Henkilotunnus),
        syntymaaika = getHenkiloTietoOrBlank(_.syntymaaika),
        markkinointilupa = Some(getValue(_.lisatiedot, (l: Map[String, String]) => l.get("lupaMarkkinointi"), "false").toBoolean),
        kiinnostunutoppisopimuksesta = Some(getValue(_.lisatiedot, (l: Map[String, String]) => l.get("kiinnostunutoppisopimuksesta").filter(_.trim.nonEmpty), "false").toBoolean),
        huoltajannimi = getHenkiloTietoOrBlank(_.huoltajannimi),
        huoltajanpuhelinnumero = getHenkiloTietoOrBlank(_.huoltajanpuhelinnumero),
        huoltajansahkoposti = getHenkiloTietoOrBlank(_.huoltajansahkoposti),
        lisakysymykset = getLisakysymykset(hakemus, lisakysymykset, hakukohdeOid),
        liitteet = getLiitteet(hakemus),
        muukoulutus = getMuukoulutus(hakemus)

      ),
      getSuoritus(pohjakoulutus, myontaja, valmistuminen, suorittaja, kieli, hakemus.personOid).toSeq,
      lahtokoulu match {
        case Some(oid) => Seq(Opiskelija(
          oppilaitosOid = lahtokoulu.get,
          henkiloOid = hakemus.personOid.getOrElse(""),
          luokkataso = getValue(_.koulutustausta, (k: Koulutustausta) => k.luokkataso),
          luokka = getValue(_.koulutustausta, (k: Koulutustausta) => k.lahtoluokka),
          alkuPaiva = DateTime.now.minus(org.joda.time.Duration.standardDays(1)),
          loppuPaiva = None,
          source = Oids.ophOrganisaatioOid
        ))
        case _ => Seq()
      },
      (for (a: HakemusAnswers <- v; t <- a.hakutoiveet) yield Hakemus(convertToiveet(t, haku), hakemus.oid, julkaisulupa, hakemus.applicationSystemId, lisapistekoulutus, Seq(), osaaminen)).getOrElse(Hakemus(Seq(), hakemus.oid, julkaisulupa, hakemus.applicationSystemId, lisapistekoulutus, Seq(), osaaminen)))
  }


  def getValue[A](key: (HakemusAnswers) => Option[A], subKey: (A) => Option[String], default: String = "")(implicit answers: Option[HakemusAnswers]): String = {
    (for (m <- answers; c <- key(m); v <- subKey(c)) yield v).getOrElse(default)
  }

  def getMuukoulutus(hakemus: FullHakemus): Option[String] = {
    hakemus.answers.flatMap(_.koulutustausta).flatMap(_.muukoulutus)
  }

  def getSuoritus(pohjakoulutus: Option[String], myontaja: String, valmistuminen: LocalDate, suorittaja: String, kieli: String, hakija: Option[String]): Option[Suoritus] = {
    Seq(pohjakoulutus).collect {
      case Some("0") => VirallinenSuoritus("ulkomainen", myontaja, if (LocalDate.now.isBefore(valmistuminen)) "KESKEN" else "VALMIS", valmistuminen, suorittaja, yksilollistaminen.Ei, kieli, vahv = false, lahde = hakija.getOrElse(Oids.ophOrganisaatioOid))
      case Some("1") => VirallinenSuoritus("peruskoulu", myontaja, if (LocalDate.now.isBefore(valmistuminen)) "KESKEN" else "VALMIS", valmistuminen, suorittaja, yksilollistaminen.Ei, kieli, vahv = false, lahde = hakija.getOrElse(Oids.ophOrganisaatioOid))
      case Some("2") => VirallinenSuoritus("peruskoulu", myontaja, if (LocalDate.now.isBefore(valmistuminen)) "KESKEN" else "VALMIS", valmistuminen, suorittaja, yksilollistaminen.Osittain, kieli, vahv = false, lahde = hakija.getOrElse(Oids.ophOrganisaatioOid))
      case Some("3") => VirallinenSuoritus("peruskoulu", myontaja, if (LocalDate.now.isBefore(valmistuminen)) "KESKEN" else "VALMIS", valmistuminen, suorittaja, yksilollistaminen.Alueittain, kieli, vahv = false, lahde = hakija.getOrElse(Oids.ophOrganisaatioOid))
      case Some("6") => VirallinenSuoritus("peruskoulu", myontaja, if (LocalDate.now.isBefore(valmistuminen)) "KESKEN" else "VALMIS", valmistuminen, suorittaja, yksilollistaminen.Kokonaan, kieli, vahv = false, lahde = hakija.getOrElse(Oids.ophOrganisaatioOid))
      case Some("9") => VirallinenSuoritus("lukio", myontaja, if (LocalDate.now.isBefore(valmistuminen)) "KESKEN" else "VALMIS", valmistuminen, suorittaja, yksilollistaminen.Ei, kieli, vahv = false, lahde = hakija.getOrElse(Oids.ophOrganisaatioOid))
    }.headOption
  }

  def findEnsimmainenAmmatillinenKielinenHakukohde(toiveet: Map[String, String], kieli: String): Int = {
    var x: Int = 0
    for{
      a <- 1 to 6
      if x == 0
    } (toiveet.getOrElse(s"preference${a}-Koulutus-id-lang", "") == kieli, toiveet.getOrElse(s"preference${a}-Koulutus-id-vocational", "")) match {
      case (true, "true") => x = a.toInt
      case _ =>
    }
    x
  }

  def convertToiveet(toiveet: Map[String, String], haku: Haku): Seq[Hakutoive] = {
    val Pattern = "preference(\\d+)-Opetuspiste-id".r
    val notEmpty = "(.+)".r
    var ensimmainenSuomenkielinenHakukohde: Int = findEnsimmainenAmmatillinenKielinenHakukohde(toiveet, "FI")
    var ensimmainenRuotsinkielinenHakukohde: Int = findEnsimmainenAmmatillinenKielinenHakukohde(toiveet, "SV")
    var ensimmainenEnglanninkielinenHakukohde: Int = findEnsimmainenAmmatillinenKielinenHakukohde(toiveet, "EN")
    var ensimmainenSaamenkielinenHakukohde: Int = findEnsimmainenAmmatillinenKielinenHakukohde(toiveet, "SE")

    val opetusPisteet: Seq[(Short, String)] = toiveet.collect {
      case (Pattern(n), notEmpty(opetusPisteId)) => (n.toShort, opetusPisteId)
    }.toSeq

    opetusPisteet.sortBy(_._1).map { case (jno, tarjoajaoid) =>
      val koulutukset = Set(Komoto("", "", tarjoajaoid, haku.koulutuksenAlkamisvuosi.map(_.toString), haku.koulutuksenAlkamiskausi.map(Kausi.fromKoodiUri)))
      val hakukohdekoodi = toiveet(s"preference$jno-Koulutus-id-aoIdentifier")
      val kaksoistutkinto = toiveet.get(s"preference${jno}_kaksoistutkinnon_lisakysymys").map(s => Try(s.toBoolean).getOrElse(false))
      val urheilijanammatillinenkoulutus = toiveet.get(s"preference${jno}_urheilijan_ammatillisen_koulutuksen_lisakysymys").
        map(s => Try(s.toBoolean).getOrElse(false))
      val harkinnanvaraisuusperuste: Option[String] = toiveet.get(s"preference$jno-discretionary-follow-up").flatMap {
        case "oppimisvaikudet" => Some("1")
        case "sosiaalisetsyyt" => Some("2")
        case "todistustenvertailuvaikeudet" => Some("3")
        case "todistustenpuuttuminen" => Some("4")
        case s => //logger.error(s"invalid discretionary-follow-up value $s");
          None
      }
      val aiempiperuminen = toiveet.get(s"preference${jno}_sora_oikeudenMenetys").map(s => Try(s.toBoolean).getOrElse(false))
      val terveys = toiveet.get(s"preference${jno}_sora_terveys").map(s => Try(s.toBoolean).getOrElse(false))
      val organisaatioParentOidPath = toiveet.getOrElse(s"preference$jno-Opetuspiste-id-parents", "")
      val hakukohdeOid = toiveet.getOrElse(s"preference$jno-Koulutus-id", "")
      val koulutuksenKieli: Option[String] = (jno == ensimmainenSuomenkielinenHakukohde, jno == ensimmainenRuotsinkielinenHakukohde, jno == ensimmainenEnglanninkielinenHakukohde, jno == ensimmainenSaamenkielinenHakukohde) match {
        case (true, false, false, false) => Some("FI")
        case (false, true, false, false) => Some("SV")
        case (false, false, true, false) => Some("EN")
        case (false, false, false, true) => Some("SE")
        case _ => None
      }
      Toive(jno, Hakukohde(koulutukset, hakukohdekoodi, hakukohdeOid), kaksoistutkinto, urheilijanammatillinenkoulutus, harkinnanvaraisuusperuste, aiempiperuminen, terveys, None, organisaatioParentOidPath, koulutuksenKieli)
    }
  }
}

case class ListHakemus(oid: String)

case class HakemusHaku(totalCount: Long, results: Seq[ListHakemus])

case class HakemusHenkilotiedot(Henkilotunnus: Option[String] = None,
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
                                huoltajansahkoposti: Option[String] = None)


case class Koulutustausta(lahtokoulu: Option[String],
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
                          muukoulutus: Option[String])

object Koulutustausta {
  def apply(): Koulutustausta = Koulutustausta(None, None, None, None, None, None, None, None, None, None, None, None, None,
    None, None, None, None, None, None, None, None, None, None, None, None, None, None, None, None, None, None, None, None, None, None)
}

case class HakemusAnswers(henkilotiedot: Option[HakemusHenkilotiedot] = None, koulutustausta: Option[Koulutustausta] = None, lisatiedot: Option[Map[String, String]] = None, hakutoiveet: Option[Map[String, String]] = None, osaaminen: Option[Map[String, String]] = None)

case class HakemusAttachmentRequest(id: String, preferenceAoId: Option[String], preferenceAoGroupId: Option[String], processingStatus: String, receptionStatus: String, applicationAttachment: ApplicationAttachment)

case class ApplicationAttachment(name: Option[Name], header: Option[Header], address: Address)

case class Name(translations: Translations)

case class Header(translations: Translations)

case class Address(recipient: String, streetAddress: String, postalCode: String, postOffice: String)

case class Translations(fi:String, sv:String, en:String)

case class PreferenceEligibility(aoId: String, status: String, source: Option[String], maksuvelvollisuus: Option[String])

case class FullHakemus(oid: String, personOid: Option[String], applicationSystemId: String, answers: Option[HakemusAnswers], state: Option[String], preferenceEligibilities: Seq[PreferenceEligibility], attachmentRequests: Seq[HakemusAttachmentRequest] = Seq()) extends Resource[String, FullHakemus] with Identified[String] {
  val source = Oids.ophOrganisaatioOid

  override def identify(identity: String): FullHakemus with Identified[String] = this

  val id = oid

  def hetu =
    for (
      foundAnswers <- answers;
      henkilo <- foundAnswers.henkilotiedot;
      henkiloHetu <- henkilo.Henkilotunnus
    ) yield henkiloHetu

  def stateValid: Boolean = state match {
    case Some(s) if Seq("ACTIVE", "INCOMPLETE").contains(s) => true
    case _ => false
  }

  def newId = oid

  override val core: AnyRef = oid
}

