package fi.vm.sade.hakurekisteri.hakija

import org.json4s._
import scala.concurrent.{ExecutionContextExecutor, Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.Try
import java.net.{URL, URLEncoder}
import com.stackmob.newman.ApacheHttpClient
import com.stackmob.newman.response.{HttpResponseCode, HttpResponse}
import com.stackmob.newman.dsl._
import scala.Some
import fi.vm.sade.hakurekisteri.rest.support.{Kausi, User}
import org.slf4j.LoggerFactory
import java.util.concurrent.{Executors, ExecutorService}
import fi.vm.sade.hakurekisteri.henkilo._
import fi.vm.sade.hakurekisteri.hakija.Hakemus
import fi.vm.sade.hakurekisteri.henkilo.Kansalaisuus
import fi.vm.sade.hakurekisteri.hakija.HakemusHaku
import scala.Some
import fi.vm.sade.hakurekisteri.hakija.ListHakemus
import fi.vm.sade.hakurekisteri.henkilo.Yhteystiedot
import fi.vm.sade.hakurekisteri.henkilo.YhteystiedotRyhma
import fi.vm.sade.hakurekisteri.hakija.FullHakemus
import fi.vm.sade.hakurekisteri.hakija.Hakija
import fi.vm.sade.hakurekisteri.hakija.Hakukohde
import fi.vm.sade.hakurekisteri.hakija.Hakutoive
import fi.vm.sade.hakurekisteri.suoritus.{Komoto, Suoritus}
import org.joda.time.{DateTime, LocalDate}
import fi.vm.sade.hakurekisteri.suoritus.yksilollistaminen._
import fi.vm.sade.hakurekisteri.hakija.Hakemus
import fi.vm.sade.hakurekisteri.henkilo.Kansalaisuus
import fi.vm.sade.hakurekisteri.hakija.HakemusHaku
import scala.Some
import fi.vm.sade.hakurekisteri.hakija.ListHakemus
import fi.vm.sade.hakurekisteri.henkilo.Kieli
import fi.vm.sade.hakurekisteri.henkilo.Yhteystiedot
import fi.vm.sade.hakurekisteri.henkilo.YhteystiedotRyhma
import fi.vm.sade.hakurekisteri.hakija.FullHakemus
import fi.vm.sade.hakurekisteri.hakija.Hakija
import fi.vm.sade.hakurekisteri.hakija.Hakukohde
import fi.vm.sade.hakurekisteri.hakija.Hakutoive
import fi.vm.sade.hakurekisteri.opiskelija.Opiskelija
import ForkedSeq._

trait Hakupalvelu {

  def getHakijat(q: HakijaQuery): Future[Seq[Hakija]]

}

class RestHakupalvelu(serviceUrl: String = "https://itest-virkailija.oph.ware.fi/haku-app")(implicit val ec: ExecutionContext) extends Hakupalvelu {
  val logger = LoggerFactory.getLogger(getClass)
  implicit val httpClient = new ApacheHttpClient
  protected implicit def jsonFormats: Formats = DefaultFormats

  override def getHakijat(q: HakijaQuery): Future[Seq[Hakija]] = {
    implicit val user:Option[User] = q.user
    selectHakijat(q).
      flatMap(_.map(fetchHakija).
      join.
      map(_.flatten))
  }


  def urlencode(s: String): String = URLEncoder.encode(s, "UTF-8")

  def getQueryParams(q: HakijaQuery): String = {
    val params: Seq[String] = Seq(
      Some("appState=ACTIVE"), Some("orgSearchExpanded=true"), Some("checkAllApplications=false"),
      Some("start=0"), Some("rows=500"),
      q.haku.map(s => "asId=" + urlencode(s)),
      q.organisaatio.map(s => "lopoid=" + urlencode(s)),
      q.hakukohdekoodi.map(s => "aoidCode=" + urlencode(s))
    ).flatten

    Try((for(i <- params; p <- List("&", i)) yield p).tail.reduce(_ + _)).getOrElse("")
  }

  def getProxyTicket(user: Option[User]): Future[String] = {
    Future(user.flatMap(_.attributePrincipal.map(_.getProxyTicketFor(serviceUrl + "/j_spring_cas_security_check"))).getOrElse(""))
  }



  def selectHakijat(q: HakijaQuery): Future[Seq[String]] = {
    find(q).map(hakemukset => {
      hakemukset.
        map(_.oid)
    })
  }

  def fetchHakija(oid: String)(implicit user: Option[User]): Future[Option[Hakija]] = {
    get(oid, user).map(_.map(RestHakupalvelu.getHakija(_)))
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

  def get(hakemusOid: String, user: Option[User]): Future[Option[FullHakemus]] = {
    val url = new URL(serviceUrl + "/applications/" + hakemusOid)
    restRequest[FullHakemus](user,url)
  }





}

object RestHakupalvelu {
  def getHakija(hakemus: FullHakemus): Hakija = {
    val lahtokoulu: Option[String] = hakemus.vastauksetMerged.flatMap(_.get("lahtokoulu"))
    val v = hakemus.vastauksetMerged
    Hakija(
      Henkilo(
        yhteystiedotRyhma = Seq(YhteystiedotRyhma(0, "yhteystietotyyppi1", "hakemus", true, Seq(
          Yhteystiedot(0, "YHTEYSTIETO_KATUOSOITE", getValue(v, "lahiosoite")),
          Yhteystiedot(1, "YHTEYSTIETO_POSTINUMERO", getValue(v, "Postinumero")),
          Yhteystiedot(2, "YHTEYSTIETO_MAA", getValue(v, "asuinmaa")),
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
        kansalaisuus = Seq(Kansalaisuus(getValue(v, "kansalaisuus"))),
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
      Seq(Suoritus(
        komo = "peruskoulu",
        myontaja = lahtokoulu.getOrElse(""),
        tila = "KESKEN",
        valmistuminen = LocalDate.now,
        henkiloOid = hakemus.personOid.getOrElse(""),
        yksilollistaminen = Ei,
        suoritusKieli = getValue(v, "perusopetuksen_kieli", "FI")
      )),
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
