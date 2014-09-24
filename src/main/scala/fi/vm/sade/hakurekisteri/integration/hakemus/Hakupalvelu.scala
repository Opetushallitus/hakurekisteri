package fi.vm.sade.hakurekisteri.integration.hakemus

import akka.actor.ActorRef
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.hakija._
import fi.vm.sade.hakurekisteri.henkilo.{Kansalaisuus, Kieli, Yhteystiedot, YhteystiedotRyhma, _}
import fi.vm.sade.hakurekisteri.opiskelija.Opiskelija
import fi.vm.sade.hakurekisteri.rest.support.{Kausi, Resource}
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.suoritus.{Komoto, Suoritus, yksilollistaminen}
import org.joda.time.{DateTime, LocalDate, MonthDay}
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.Try

trait Hakupalvelu {
  def getHakijat(q: HakijaQuery): Future[Seq[Hakija]]
}

class AkkaHakupalvelu(hakemusActor:ActorRef)(implicit val ec: ExecutionContext) extends Hakupalvelu {
  val logger = LoggerFactory.getLogger(getClass)
  val Pattern = "preference(\\d+).*".r

  def filterHakemus(optionField: Option[String], filterFunc: (String) => (Map[String, String]) => Map[String, String] )(fh: FullHakemus): FullHakemus = optionField match {
    case Some(field) => fh.copy(/*answers = newAnswers(fh.answers, filterFunc(field))*/)
    case None => fh
  }

  def newAnswers(answers:  Option[Map[String, Map[String, String]]], toiveFilter: (Map[String, String]) => Map[String, String]) :  Option[Map[String, Map[String, String]]] =
    answers.map((ans) => ans.get("hakutoiveet").fold(ans)((hts: Map[String, String]) => ans + ("hakutoiveet" -> toiveFilter(hts))))

  def newToiveet(oid: String)(toiveet: Map[String, String]): Map[String, String] = toiveet.filterKeys {
    case Pattern(jno) => toiveet.getOrElse(s"preference$jno-Opetuspiste-id-parents", "").split(",").toSet.contains(oid) || toiveet.getOrElse(s"preference$jno-Opetuspiste-id", "") == oid
    case _ => true
  }

  def newToiveetForKoodi(koodi: String)(toiveet: Map[String, String]): Map[String, String] = toiveet.filterKeys {
    case Pattern(jno) => toiveet.getOrElse(s"preference$jno-Koulutus-id-aoIdentifier", "") == koodi
    case _ => true
  }

  def filterState(fh: FullHakemus): Boolean = fh.state.exists((s) => s == "ACTIVE" || s == "INCOMPLETE")

  override def getHakijat(q: HakijaQuery): Future[Seq[Hakija]] = {
    import akka.pattern._
    implicit val timeout: Timeout = 60.seconds
    (hakemusActor ? HakemusQuery(q)).mapTo[Seq[FullHakemus]]
      .map(_.
      withFilter(filterState).
      map(filterHakemus(q.organisaatio, newToiveet)).
      map(filterHakemus(q.hakukohdekoodi, newToiveetForKoodi)).
      map(AkkaHakupalvelu.getHakija))
  }
}

object AkkaHakupalvelu {
  val logger = LoggerFactory.getLogger(getClass)

  val DEFAULT_POHJA_KOULUTUS: String = "1"

  def getVuosi(vastaukset:Koulutustausta)(pohjakoulutus:String): Option[String] = pohjakoulutus match {
    case "9" => vastaukset.lukioPaattotodistusVuosi
    case "7" => Some((LocalDate.now.getYear + 1).toString)
    case _ => vastaukset.PK_PAATTOTODISTUSVUOSI
  }


  def kaydytLisapisteKoulutukset(tausta: Koulutustausta) =
    {

      def checkKoulutus(lisakoulutus: Option[String]): Boolean = {
        Try(lisakoulutus.getOrElse("false").toBoolean).getOrElse(false)
      }
      Map(
        "LISAKOULUTUS_KYMPPI" -> tausta.LISAKOULUTUS_KYMPPI,
        "LISAKOULUTUS_VAMMAISTEN" -> tausta.LISAKOULUTUS_VAMMAISTEN,
        "LISAKOULUTUS_TALOUS" -> tausta.LISAKOULUTUS_TALOUS,
        "LISAKOULUTUS_AMMATTISTARTTI" -> tausta.LISAKOULUTUS_AMMATTISTARTTI,
        "LISAKOULUTUS_KANSANOPISTO" -> tausta.LISAKOULUTUS_KANSANOPISTO,
        "LISAKOULUTUS_MAAHANMUUTTO" -> tausta.LISAKOULUTUS_MAAHANMUUTTO
      ).mapValues(checkKoulutus).filter{case (_, done) => done}.keys
    }

  def getHakija(hakemus: FullHakemus): Hakija = {
    val kesa = new MonthDay(6, 4)
    implicit val v = hakemus.answers
    val koulutustausta = for (a: HakemusAnswers <- v; k: Koulutustausta <- a.koulutustausta) yield k
    val lahtokoulu: Option[String] = for(k <- koulutustausta; l <- k.lahtokoulu) yield l
    val pohjakoulutus: Option[String] = for (k <- koulutustausta; p <- k.POHJAKOULUTUS) yield p
    val todistusVuosi: Option[String] = for (p: String <- pohjakoulutus;k <- koulutustausta; v <- getVuosi(k)(p)) yield v
    val kieli = (for(a <- v; henkilotiedot: HakemusHenkilotiedot <- a.henkilotiedot; aidinkieli <- henkilotiedot.aidinkieli) yield aidinkieli).getOrElse("FI")
    val myontaja = lahtokoulu.getOrElse("")
    val suorittaja = hakemus.personOid.getOrElse("")
    val valmistuminen = todistusVuosi.flatMap(vuosi => Try(kesa.toLocalDate(vuosi.toInt)).toOption).getOrElse(new LocalDate(0))
    val julkaisulupa = (for(
      a <- v;
      lisatiedot <- a.lisatiedot;
      julkaisu <- lisatiedot.lupaJulkaisu
    ) yield julkaisu).getOrElse("false").toBoolean

    val lisapistekoulutus = for (
      tausta <- koulutustausta;
      lisatausta <- kaydytLisapisteKoulutukset(tausta).headOption
    ) yield lisatausta
    val henkilotiedot: Option[HakemusHenkilotiedot] = for(a <- v; henkilotiedot <- a.henkilotiedot) yield henkilotiedot
    def getHenkiloTietoOrElse(f: (HakemusHenkilotiedot) => Option[String], orElse: String) =
      (for (h <- henkilotiedot; osoite <- f(h)) yield osoite).getOrElse(orElse)

    def getHenkiloTietoOrBlank(f: (HakemusHenkilotiedot) => Option[String]): String = getHenkiloTietoOrElse(f, "")
    Hakija(
      Henkilo(
        yhteystiedotRyhma = Seq(YhteystiedotRyhma(0, "yhteystietotyyppi1", "hakemus", readOnly = true, Seq(
          Yhteystiedot(0, "YHTEYSTIETO_KATUOSOITE", getHenkiloTietoOrBlank(_.lahiosoite)),
          Yhteystiedot(1, "YHTEYSTIETO_POSTINUMERO", getHenkiloTietoOrBlank(_.Postinumero)),
          Yhteystiedot(2, "YHTEYSTIETO_MAA", getHenkiloTietoOrElse(_.asuinmaa,"FIN")),
          Yhteystiedot(3, "YHTEYSTIETO_MATKAPUHELIN", getHenkiloTietoOrBlank(_.matkapuhelinnumero1)),
          Yhteystiedot(4, "YHTEYSTIETO_SAHKOPOSTI", getHenkiloTietoOrBlank(_.Sähköposti)),
          Yhteystiedot(5, "YHTEYSTIETO_KAUPUNKI", getHenkiloTietoOrBlank(_.kotikunta))
        ))),
        yksiloity = false,
        sukunimi = getHenkiloTietoOrBlank(_.Sukunimi),
        etunimet = getHenkiloTietoOrBlank(_.Etunimet),
        kutsumanimi = getHenkiloTietoOrBlank(_.Kutsumanimi),
        kielisyys = Seq(),
        yksilointitieto = None,
        henkiloTyyppi = "OPPIJA",
        oidHenkilo = hakemus.personOid.getOrElse(""),
        duplicate = false,
        oppijanumero = hakemus.personOid.getOrElse(""),
        kayttajatiedot = None,
        kansalaisuus = Seq(Kansalaisuus(getHenkiloTietoOrElse(_.kansalaisuus, "FIN"))),
        passinnumero = "",
        asiointiKieli = Kieli(kieli),
        passivoitu = false,
        eiSuomalaistaHetua = getHenkiloTietoOrElse(_.onkoSinullaSuomalainenHetu, "false").toBoolean,
        sukupuoli = getHenkiloTietoOrBlank(_.sukupuoli),
        hetu = getHenkiloTietoOrBlank(_.Henkilotunnus),
        syntymaaika = getHenkiloTietoOrBlank(_.syntymaaika),
        turvakielto = false,
        markkinointilupa = Some(getValue(_.lisatiedot,(l:Lisatiedot) => l.lupaMarkkinointi, "false").toBoolean)
      ),
      getSuoritukset(pohjakoulutus, myontaja, valmistuminen, suorittaja, kieli),
      lahtokoulu match {
        case Some(oid) => Seq(Opiskelija(
          oppilaitosOid = lahtokoulu.get,
          henkiloOid = hakemus.personOid.getOrElse(""),
          luokkataso = getValue(_.koulutustausta,(k:Koulutustausta) =>  k.luokkataso),
          luokka = getValue(_.koulutustausta, (k:Koulutustausta) => k.lahtoluokka),
          alkuPaiva = DateTime.now.minus(org.joda.time.Duration.standardDays(1)),
          loppuPaiva = None,
          source = "1.2.246.562.10.00000000001"
        ))
        case _ => Seq()
      },
      (for (a: HakemusAnswers <- v; t <- a.hakutoiveet) yield Hakemus(convertToiveet(t), hakemus.oid, julkaisulupa, hakemus.applicationSystemId, lisapistekoulutus)).getOrElse(Hakemus(Seq(), hakemus.oid, julkaisulupa, hakemus.applicationSystemId, lisapistekoulutus))
    )

  }

  def getValue[A](key: (HakemusAnswers) => Option[A], subKey: (A) => Option[String], default: String = "")(implicit answers: Option[HakemusAnswers]): String = {
    (for (m <- answers; c <- key(m); v <- subKey(c)) yield v).getOrElse(default)
  }

  def getSuoritukset(pohjakoulutus: Option[String], myontaja: String, valmistuminen: LocalDate, suorittaja: String, kieli: String): Seq[Suoritus] = {
    Seq(pohjakoulutus).collect {
      case Some("0") => Suoritus("ulkomainen", myontaja, if (LocalDate.now.isBefore(valmistuminen)) "KESKEN" else "VALMIS", valmistuminen, suorittaja, yksilollistaminen.Ei, kieli, source = "1.2.246.562.10.00000000001")
      case Some("1") => Suoritus("peruskoulu", myontaja, if (LocalDate.now.isBefore(valmistuminen)) "KESKEN" else "VALMIS", valmistuminen, suorittaja, yksilollistaminen.Ei, kieli, source = "1.2.246.562.10.00000000001")
      case Some("2") => Suoritus("peruskoulu", myontaja, if (LocalDate.now.isBefore(valmistuminen)) "KESKEN" else "VALMIS", valmistuminen, suorittaja, yksilollistaminen.Osittain, kieli, source = "1.2.246.562.10.00000000001")
      case Some("3") => Suoritus("peruskoulu", myontaja, if (LocalDate.now.isBefore(valmistuminen)) "KESKEN" else "VALMIS", valmistuminen, suorittaja, yksilollistaminen.Alueittain, kieli, source = "1.2.246.562.10.00000000001")
      case Some("6") => Suoritus("peruskoulu", myontaja, if (LocalDate.now.isBefore(valmistuminen)) "KESKEN" else "VALMIS", valmistuminen, suorittaja, yksilollistaminen.Kokonaan, kieli, source = "1.2.246.562.10.00000000001")
      case Some("9") => Suoritus("lukio", myontaja, if (LocalDate.now.isBefore(valmistuminen)) "KESKEN" else "VALMIS", valmistuminen, suorittaja, yksilollistaminen.Ei, kieli, source = "1.2.246.562.10.00000000001")
    }
  }

  def convertToiveet(toiveet: Map[String, String]): Seq[Hakutoive] = {
    val Pattern = "preference(\\d+)-Opetuspiste-id".r
    val notEmpty = "(.+)".r
    val opetusPisteet: Seq[(Short, String)] = toiveet.collect {
      case (Pattern(n), notEmpty(opetusPisteId)) => (n.toShort, opetusPisteId)
    }.toSeq

    opetusPisteet.sortBy(_._1).map { case (jno, tarjoajaoid) =>
      val koulutukset = Set(Komoto("", "", tarjoajaoid, "2014", Kausi.Syksy))
      val hakukohdekoodi = toiveet(s"preference$jno-Koulutus-id-aoIdentifier")
      val kaksoistutkinto = toiveet.get(s"preference${jno}_kaksoistutkinnon_lisakysymys").map(s => Try(s.toBoolean).getOrElse(false))
      val urheilijanammatillinenkoulutus = toiveet.get(s"preference${jno}_urheilijan_ammatillisen_koulutuksen_lisakysymys").
        map(s => Try(s.toBoolean).getOrElse(false))
      val harkinnanvaraisuusperuste: Option[String] = toiveet.get(s"preference$jno-discretionary-follow-up").flatMap {
        case "oppimisvaikudet" => Some("1")
        case "sosiaalisetsyyt" => Some("2")
        case "todistustenvertailuvaikeudet" => Some("3")
        case "todistustenpuuttuminen" => Some("4")
        case s => logger.error(s"invalid discretionary-follow-up value $s"); None
      }
      val aiempiperuminen = toiveet.get(s"preference${jno}_sora_oikeudenMenetys").map(s => Try(s.toBoolean).getOrElse(false))
      val terveys = toiveet.get(s"preference${jno}_sora_terveys").map(s => Try(s.toBoolean).getOrElse(false))
      val hakukohdeOid = toiveet.get(s"preference$jno-Koulutus-id").getOrElse("")
      Toive(jno, Hakukohde(koulutukset, hakukohdekoodi, hakukohdeOid), kaksoistutkinto, urheilijanammatillinenkoulutus, harkinnanvaraisuusperuste, aiempiperuminen, terveys)
    }
  }


}

case class ListHakemus(oid: String)

case class HakemusHaku(totalCount: Long, results: Seq[ListHakemus])

case class HakemusHenkilotiedot(Henkilotunnus: Option[String],
                                aidinkieli: Option[String],
                                lahiosoite: Option[String],
                                Postinumero: Option[String],
                                asuinmaa: Option[String],
                                matkapuhelinnumero1: Option[String],
                                Sähköposti: Option[String],
                                kotikunta: Option[String],
                                Sukunimi: Option[String],
                                Etunimet: Option[String],
                                Kutsumanimi: Option[String],
                                kansalaisuus: Option[String],
                                onkoSinullaSuomalainenHetu: Option[String],
                                sukupuoli: Option[String],
                                syntymaaika: Option[String])

case class Koulutustausta(lahtokoulu:Option[String],
                          POHJAKOULUTUS: Option[String],
                          lukioPaattotodistusVuosi: Option[String],
                          PK_PAATTOTODISTUSVUOSI: Option[String],
                          LISAKOULUTUS_KYMPPI: Option[String],
                          LISAKOULUTUS_VAMMAISTEN: Option[String],
                          LISAKOULUTUS_TALOUS: Option[String],
                          LISAKOULUTUS_AMMATTISTARTTI: Option[String],
                          LISAKOULUTUS_KANSANOPISTO: Option[String],
                          LISAKOULUTUS_MAAHANMUUTTO: Option[String],
                          luokkataso: Option[String],
                          lahtoluokka: Option[String])

case class Lisatiedot(lupaJulkaisu: Option[String], lupaMarkkinointi: Option[String])

case class HakemusAnswers(henkilotiedot: Option[HakemusHenkilotiedot], koulutustausta: Option[Koulutustausta], lisatiedot: Option[Lisatiedot], hakutoiveet: Option[Map[String, String]])

case class FullHakemus(oid: String, personOid: Option[String], applicationSystemId: String, answers: Option[HakemusAnswers], state: Option[String]) extends Resource[String] {
  val source = "1.2.246.562.10.00000000001"
  override def identify(id: String): this.type with Identified[String] = FullHakemus.identify(this,id).asInstanceOf[this.type with Identified[String]]

  def hetu =
      for (
        foundAnswers <- answers;
        henkilo <- foundAnswers.henkilotiedot;
        henkiloHetu <- henkilo.Henkilotunnus
      ) yield henkiloHetu



}

object FullHakemus {
  def identify(o:FullHakemus): FullHakemus with Identified[String] = o.identify(o.oid)

  def identify(o:FullHakemus, identity:String) =
  new FullHakemus(
      o.oid: String,
      o.personOid: Option[String],
      o.applicationSystemId: String,
      o.answers,
      o.state: Option[String]) with Identified[String] {
        val id: String = identity
      }
}