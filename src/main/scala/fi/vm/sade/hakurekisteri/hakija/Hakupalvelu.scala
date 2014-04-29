package fi.vm.sade.hakurekisteri.hakija

import org.json4s._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import java.net.{URL, URLEncoder}
import com.stackmob.newman.ApacheHttpClient
import com.stackmob.newman.response.HttpResponseCode
import com.stackmob.newman.dsl._
import fi.vm.sade.hakurekisteri.rest.support.{Kausi, User}
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

trait Hakupalvelu {

  def getHakijat(q: HakijaQuery): Future[Seq[Hakija]]

}

class RestHakupalvelu(serviceUrl: String = "https://itest-virkailija.oph.ware.fi/haku-app", maxApplications: Integer = 2000)(implicit val ec: ExecutionContext) extends Hakupalvelu {
  val logger = LoggerFactory.getLogger(getClass)
  implicit val httpClient = new ApacheHttpClient()()
  protected implicit def jsonFormats: Formats = DefaultFormats



  override def getHakijat(q: HakijaQuery): Future[Seq[Hakija]] = {
    val url = new URL(serviceUrl + "/applications/listfull?" + getQueryParams(q))
    val user = q.user
    def f(foo: Option[List[FullHakemus]]): Seq[Hakija] = {
      logger.info("got result for: %s".format(url))
      foo.getOrElse(Seq()).map(RestHakupalvelu.getHakija)
    }
    restRequest[List[FullHakemus]](user, url).map(f)
  }



  def urlencode(s: String): String = URLEncoder.encode(s, "UTF-8")

  def getQueryParams(q: HakijaQuery): String = {
    val params: Seq[String] = Seq(
      Some("appState=ACTIVE"), Some("orgSearchExpanded=true"), Some("checkAllApplications=false"),
      Some("start=0"), Some("rows=" + maxApplications),
      q.haku.map(s => "asId=" + urlencode(s)),
      q.organisaatio.map(s => "lopoid=" + urlencode(s)),
      q.hakukohdekoodi.map(s => "aoidCode=" + urlencode(s))
    ).flatten

    Try((for(i <- params; p <- List("&", i)) yield p).tail.reduce(_ + _)).getOrElse("")
  }

  def getProxyTicket(user: Option[User]): Future[String] = {
    Future(user.flatMap(_.attributePrincipal.map(_.getProxyTicketFor(serviceUrl + "/j_spring_cas_security_check"))).getOrElse(""))
  }





  def find(q: HakijaQuery): Future[Seq[ListHakemus]] = {
    val url = new URL(serviceUrl + "/applications/list/fullName/asc?" + getQueryParams(q))
    val user = q.user
    restRequest[HakemusHaku](user, url).map(_.map(_.results).getOrElse(Seq()))
  }


  def restRequest[A <: AnyRef](user: Option[User], url: URL)(implicit mf : Manifest[A]): Future[Option[A]] = {
    getProxyTicket(user).flatMap((ticket) => {
      logger.debug("calling haku-app [url={}, ticket={}]", url, ticket)

      GET(url).addHeaders("CasSecurityTicket" -> ticket).apply.map(response => {
        if (response.code == HttpResponseCode.Ok) {
          val hakemusHaku = response.bodyAsCaseClass[A].toOption
          logger.debug("got response: [{}]", hakemusHaku)

          hakemusHaku
        } else {
          logger.error("call to haku-app [url={}, ticket={}] failed: {}", url, ticket, response.code)

          throw new RuntimeException("virhe kutsuttaessa hakupalvelua: %s".format(response.code))
        }
      })
    })
  }


}

object RestHakupalvelu {

  val DEFAULT_POHJA_KOULUTUS: String = "1"

  def getVuosi(vastaukset:Option[Map[String,String]])(pohjakoulutus:String) = pohjakoulutus match {
    case "9" => vastaukset.flatMap(_.get("lukioPaattotodistusVuosi"))
    case "7" => Some((LocalDate.now.getYear + 1).toString)
    case _ => vastaukset.flatMap(_.get("PK_PAATTOTODISTUSVUOSI"))
  }



  def getHakija(hakemus: FullHakemus): Hakija = {
    val kesa = new MonthDay(6,4)
    val lahtokoulu: Option[String] = hakemus.vastauksetMerged.flatMap(_.get("lahtokoulu"))
    val pohjakoulutus: Option[String] = hakemus.vastauksetMerged.flatMap(_.get("POHJAKOULUTUS"))
    val todistusVuosi: Option[String] = pohjakoulutus.flatMap(getVuosi(hakemus.vastauksetMerged)(_))
    val v = hakemus.vastauksetMerged
    val kieli  = getValue(v, "perusopetuksen_kieli", "FI")
    val myontaja = lahtokoulu.getOrElse("")
    val suorittaja = hakemus.personOid.getOrElse("")
    val valmistuminen = todistusVuosi.map(vuosi => kesa.toLocalDate(vuosi.toInt)).getOrElse(new LocalDate(0))

    Hakija(
      Henkilo(
        yhteystiedotRyhma = Seq(YhteystiedotRyhma(0, "yhteystietotyyppi1", "hakemus", readOnly = true, Seq(
          Yhteystiedot(0, "YHTEYSTIETO_KATUOSOITE", getValue(v, "lahiosoite")),
          Yhteystiedot(1, "YHTEYSTIETO_POSTINUMERO", getValue(v, "Postinumero")),
          Yhteystiedot(2, "YHTEYSTIETO_MAA", getValue(v, "asuinmaa", "FIN")),
          Yhteystiedot(3, "YHTEYSTIETO_MATKAPUHELIN", getValue(v, "matkapuhelinnumero1")),
          Yhteystiedot(4, "YHTEYSTIETO_SAHKOPOSTI", getValue(v, "Sähköposti")),
          Yhteystiedot(5, "YHTEYSTIETO_KAUPUNKI", getValue(v, "kotikunta"))
        ))),
        yksiloity = false,
        sukunimi = getValue(v, "Sukunimi"),
        etunimet = getValue(v, "Etunimet"),
        kutsumanimi = getValue(v, "Kutsumanimi"),
        kielisyys = Seq(),
        yksilointitieto = None,
        henkiloTyyppi = "OPPIJA",
        oidHenkilo = hakemus.personOid.getOrElse(""),
        duplicate = false,
        oppijanumero = hakemus.personOid.getOrElse(""),
        kayttajatiedot = None,
        kansalaisuus = Seq(Kansalaisuus(getValue(v, "kansalaisuus", "FIN"))),
        passinnumero = "",
        asiointiKieli = Kieli("FI", "FI"),
        passivoitu = false,
        eiSuomalaistaHetua = getValue(v, "onkoSinullaSuomalainenHetu", "false").toBoolean,
        sukupuoli = getValue(v, "sukupuoli"),
        hetu = getValue(v, "Henkilotunnus"),
        syntymaaika = getValue(v, "syntymaaika"),
        turvakielto = false,
        markkinointilupa = Some(getValue(v, "lupaMarkkinointi", "false").toBoolean)
      ),
      getSuoritukset(pohjakoulutus, myontaja, valmistuminen, suorittaja, kieli),
      lahtokoulu match {
        case Some(oid) => Seq(Opiskelija(
          oppilaitosOid = lahtokoulu.get,
          henkiloOid = hakemus.personOid.getOrElse(""),
          luokkataso = getValue(v, "luokkataso"),
          luokka = getValue(v, "lahtoluokka"),
          alkuPaiva = DateTime.now.minus(org.joda.time.Duration.standardDays(1)),
          loppuPaiva = None
        ))
        case _ => Seq()
      },
      v.map(toiveet => {
        val hakutoiveet = convertToiveet(toiveet)
        Hakemus(hakutoiveet, hakemus.oid)
      }).getOrElse(Hakemus(Seq(), hakemus.oid))
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

    opetusPisteet.sortBy(_._1).map((t) => {
      val koulutukset = Set(Komoto("", "", t._2, "2014", Kausi.Syksy))
      val hakukohdekoodi = toiveet("preference" + t._1 + "-Koulutus-id-aoIdentifier")
      Hakutoive(Hakukohde(koulutukset, hakukohdekoodi), Try(toiveet("preference" + t._1 + "-Koulutus-id-kaksoistutkinto").toBoolean).getOrElse(false))
    })
  }

  def getValue(h: Option[Map[String, String]], key: String, default: String = ""): String = {
    h.flatMap(_.get(key)).getOrElse(default)
  }

}

case class ListHakemus(oid: String)

case class HakemusHaku(totalCount: Long, results: Seq[ListHakemus])

case class FullHakemus(oid: String, personOid: Option[String], applicationSystemId: String, vastauksetMerged: Option[Map[String, String]])
