package fi.vm.sade.hakurekisteri.hakija

import org.json4s._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import java.net.{URL, URLEncoder}
import com.stackmob.newman.ApacheHttpClient
import com.stackmob.newman.response.HttpResponseCode
import com.stackmob.newman.dsl._
import fi.vm.sade.hakurekisteri.rest.support.{Resource, Kausi, User}
import org.slf4j.LoggerFactory
import fi.vm.sade.hakurekisteri.henkilo._
import fi.vm.sade.hakurekisteri.suoritus.{yksilollistaminen, Komoto, Suoritus}
import org.joda.time.{MonthDay, DateTime, LocalDate}
import fi.vm.sade.hakurekisteri.henkilo.Kansalaisuus
import scala.Some
import fi.vm.sade.hakurekisteri.henkilo.Kieli
import fi.vm.sade.hakurekisteri.henkilo.Yhteystiedot
import fi.vm.sade.hakurekisteri.henkilo.YhteystiedotRyhma
import fi.vm.sade.hakurekisteri.opiskelija.Opiskelija
import scala.annotation.tailrec
import java.util.UUID
import fi.vm.sade.hakurekisteri.storage.Identified
import akka.actor.ActorRef
import akka.util.Timeout

trait Hakupalvelu {

  def getHakijat(q: HakijaQuery): Future[Seq[Hakija]]


}

class AkkaHakupalvelu(hakemusActor:ActorRef)(implicit val ec: ExecutionContext) extends Hakupalvelu {
  val logger = LoggerFactory.getLogger(getClass)


  import scala.concurrent.duration._



  val Pattern = "preference(\\d+).*".r

  def filterHakemus(optionField: Option[String], filterFunc: (String) => (Map[String, String]) => Map[String, String] )(fh: FullHakemus): FullHakemus = optionField match {
    case Some(field) => fh.copy(answers = newAnswers(fh.answers, filterFunc(field)))
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

  def filterState(fh: FullHakemus): Boolean = fh.state.map((s) => s == "ACTIVE" || s == "INCOMPLETE").getOrElse(false)





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

  def getVuosi(vastaukset:Option[Map[String,String]])(pohjakoulutus:String): Option[String] = pohjakoulutus match {
    case "9" => vastaukset.flatMap(_.get("lukioPaattotodistusVuosi"))
    case "7" => Some((LocalDate.now.getYear + 1).toString)
    case _ => vastaukset.flatMap(_.get("PK_PAATTOTODISTUSVUOSI"))
  }

  def getHakija(hakemus: FullHakemus): Hakija = {
    val kesa = new MonthDay(6,4)
    implicit val v = hakemus.answers
    val koulutustausta = for (a <- v; k <- a.get("koulutustausta")) yield k
    val lahtokoulu: Option[String] = for(k <- koulutustausta; l <- k.get("lahtokoulu")) yield l
    val pohjakoulutus: Option[String] = for (k <- koulutustausta; p <- k.get("POHJAKOULUTUS")) yield p
    val todistusVuosi: Option[String] = for (p: String <- pohjakoulutus; v <- getVuosi(koulutustausta)(p)) yield v
    val kieli: String = getValue("henkilotiedot", "aidinkieli", "FI")
    val myontaja = lahtokoulu.getOrElse("")
    val suorittaja = hakemus.personOid.getOrElse("")
    val valmistuminen = todistusVuosi.flatMap(vuosi => Try(kesa.toLocalDate(vuosi.toInt)).toOption).getOrElse(new LocalDate(0))
    val julkaisulupa = Some(getValue("lisatiedot", "lupaJulkaisu", "false").toBoolean)

    Hakija(
      Henkilo(
        yhteystiedotRyhma = Seq(YhteystiedotRyhma(0, "yhteystietotyyppi1", "hakemus", readOnly = true, Seq(
          Yhteystiedot(0, "YHTEYSTIETO_KATUOSOITE", getValue("henkilotiedot", "lahiosoite")),
          Yhteystiedot(1, "YHTEYSTIETO_POSTINUMERO", getValue("henkilotiedot", "Postinumero")),
          Yhteystiedot(2, "YHTEYSTIETO_MAA", getValue("henkilotiedot", "asuinmaa", "FIN")),
          Yhteystiedot(3, "YHTEYSTIETO_MATKAPUHELIN", getValue("henkilotiedot", "matkapuhelinnumero1")),
          Yhteystiedot(4, "YHTEYSTIETO_SAHKOPOSTI", getValue("henkilotiedot", "Sähköposti")),
          Yhteystiedot(5, "YHTEYSTIETO_KAUPUNKI", getValue("henkilotiedot", "kotikunta"))
        ))),
        yksiloity = false,
        sukunimi = getValue("henkilotiedot", "Sukunimi"),
        etunimet = getValue("henkilotiedot", "Etunimet"),
        kutsumanimi = getValue("henkilotiedot", "Kutsumanimi"),
        kielisyys = Seq(),
        yksilointitieto = None,
        henkiloTyyppi = "OPPIJA",
        oidHenkilo = hakemus.personOid.getOrElse(""),
        duplicate = false,
        oppijanumero = hakemus.personOid.getOrElse(""),
        kayttajatiedot = None,
        kansalaisuus = Seq(Kansalaisuus(getValue("henkilotiedot", "kansalaisuus", "FIN"))),
        passinnumero = "",
        asiointiKieli = Kieli(kieli),
        passivoitu = false,
        eiSuomalaistaHetua = getValue("henkilotiedot", "onkoSinullaSuomalainenHetu", "false").toBoolean,
        sukupuoli = getValue("henkilotiedot", "sukupuoli"),
        hetu = getValue("henkilotiedot", "Henkilotunnus"),
        syntymaaika = getValue("henkilotiedot", "syntymaaika"),
        turvakielto = false,
        markkinointilupa = Some(getValue("lisatiedot", "lupaMarkkinointi", "false").toBoolean)
      ),
      getSuoritukset(pohjakoulutus, myontaja, valmistuminen, suorittaja, kieli),
      lahtokoulu match {
        case Some(oid) => Seq(Opiskelija(
          oppilaitosOid = lahtokoulu.get,
          henkiloOid = hakemus.personOid.getOrElse(""),
          luokkataso = getValue("koulutustausta", "luokkataso"),
          luokka = getValue("koulutustausta", "lahtoluokka"),
          alkuPaiva = DateTime.now.minus(org.joda.time.Duration.standardDays(1)),
          loppuPaiva = None
        ))
        case _ => Seq()
      },
      (for (a <- v; t <- a.get("hakutoiveet")) yield Hakemus(convertToiveet(t), hakemus.oid, julkaisulupa, hakemus.applicationSystemId)).getOrElse(Hakemus(Seq(), hakemus.oid, julkaisulupa, hakemus.applicationSystemId))
    )
  }


  def getSuoritukset(pohjakoulutus: Option[String], myontaja: String, valmistuminen: LocalDate, suorittaja: String, kieli: String): Seq[Suoritus] = {
    Seq(pohjakoulutus).collect {
      case Some("0") => Suoritus("ulkomainen", myontaja, if (LocalDate.now.isBefore(valmistuminen)) "KESKEN" else "VALMIS", valmistuminen, suorittaja, yksilollistaminen.Ei, kieli)
      case Some("1") => Suoritus("peruskoulu", myontaja, if (LocalDate.now.isBefore(valmistuminen)) "KESKEN" else "VALMIS", valmistuminen, suorittaja, yksilollistaminen.Ei, kieli)
      case Some("2") => Suoritus("peruskoulu", myontaja, if (LocalDate.now.isBefore(valmistuminen)) "KESKEN" else "VALMIS", valmistuminen, suorittaja, yksilollistaminen.Osittain, kieli)
      case Some("3") => Suoritus("peruskoulu", myontaja, if (LocalDate.now.isBefore(valmistuminen)) "KESKEN" else "VALMIS", valmistuminen, suorittaja, yksilollistaminen.Alueittain, kieli)
      case Some("6") => Suoritus("peruskoulu", myontaja, if (LocalDate.now.isBefore(valmistuminen)) "KESKEN" else "VALMIS", valmistuminen, suorittaja, yksilollistaminen.Kokonaan, kieli)
      case Some("9") => Suoritus("lukio", myontaja, if (LocalDate.now.isBefore(valmistuminen)) "KESKEN" else "VALMIS", valmistuminen, suorittaja, yksilollistaminen.Ei, kieli)
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

  def getValue(key: String, subKey: String, default: String = "")(implicit answers: Option[Map[String, Map[String, String]]]): String = {
    (for (m <- answers; c <- m.get(key); v <- c.get(subKey)) yield v).getOrElse(default)
  }
}

case class ListHakemus(oid: String)

case class HakemusHaku(totalCount: Long, results: Seq[ListHakemus])

case class FullHakemus(oid: String, personOid: Option[String], applicationSystemId: String, answers: Option[Map[String, Map[String, String]]], state: Option[String]) extends Resource[String] {
  override def identify(id: String): this.type with Identified[String] = FullHakemus.identify(this,id).asInstanceOf[this.type with Identified[String]]
}


object FullHakemus {

  def identify(o:FullHakemus): FullHakemus with Identified[String] = o.identify(o.oid)

  def identify(o:FullHakemus, identity:String) =
  new FullHakemus(
      o.oid: String,
      o.personOid: Option[String],
      o.applicationSystemId: String,
      o.answers: Option[Map[String, Map[String, String]]],
      o.state: Option[String]) with Identified[String] {
        val id: String = identity
      }


}