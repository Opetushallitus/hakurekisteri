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
import scala.annotation.tailrec

trait Hakupalvelu {

  def getHakijat(q: HakijaQuery): Future[Seq[Hakija]]

}

class RestHakupalvelu(serviceUrl: String = "https://itest-virkailija.oph.ware.fi/haku-app", maxApplications: Int = 2000)(implicit val ec: ExecutionContext) extends Hakupalvelu {
  val logger = LoggerFactory.getLogger(getClass)


  import scala.concurrent.duration._

  implicit val httpClient = new ApacheHttpClient(socketTimeout = 60.seconds.toMillis.toInt)()
  protected implicit def jsonFormats: Formats = DefaultFormats



  override def getHakijat(q: HakijaQuery): Future[Seq[Hakija]] = {
    def getUrl(page: Int = 0): URL = {
      new URL(serviceUrl + "/applications/listfull?" + getQueryParams(q, page))
    }
    val user = q.user

    val future: Future[Option[List[FullHakemus]]] = restRequest[List[FullHakemus]](user, getUrl())

    def getAll(cur: List[FullHakemus])(res: Option[List[FullHakemus]]):Future[Option[List[FullHakemus]]] = res match {
      case None                                   => Future.successful(None)
      case Some(l) if l.length < maxApplications  => Future.successful(Some(cur ++ l))
      case Some(l)                                => restRequest[List[FullHakemus]](user, getUrl((cur.length / maxApplications) + 1)).flatMap(getAll(cur ++ l))
    }

    def f(foo: Option[List[FullHakemus]]): Seq[Hakija] = {

      foo.getOrElse(Seq()).map(RestHakupalvelu.getHakija)
    }

    future.flatMap(getAll(List())).map(f)
  }



  def urlencode(s: String): String = URLEncoder.encode(s, "UTF-8")

  def getQueryParams(q: HakijaQuery, page: Int = 0): String = {
    val params: Seq[String] = Seq(
      Some("appState=ACTIVE"), Some("orgSearchExpanded=true"), Some("checkAllApplications=false"),
      Some("start=%d" format (page * maxApplications)), Some("rows=" + maxApplications),
      q.haku.map(s => "asId=" + urlencode(s)),
      q.organisaatio.map(s => "lopoid=" + urlencode(s)),
      q.hakukohdekoodi.map(s => "aoid=" + urlencode(s))
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
          logger.debug("got response for url: [{}]", url)

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
    val kieli = getValue("koulutustausta", "perusopetuksen_kieli", "FI")
    val myontaja = lahtokoulu.getOrElse("")
    val suorittaja = hakemus.personOid.getOrElse("")
    val valmistuminen = todistusVuosi.map(vuosi => kesa.toLocalDate(vuosi.toInt)).getOrElse(new LocalDate(0))
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
        asiointiKieli = Kieli("FI", "FI"),
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
      (for (a <- v; t <- a.get("hakutoiveet")) yield Hakemus(convertToiveet(t), hakemus.oid, julkaisulupa)).getOrElse(Hakemus(Seq(), hakemus.oid, julkaisulupa))
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
      val kaksoistutkinto = toiveet.get("preference" + t._1 + "_kaksoistutkinnon_lisakysymys").map(s => Try(s.toBoolean).getOrElse(false))
      val urheilijanammatillinenkoulutus = toiveet.get("preference" + t._1 + "_urheilijan_ammatillisen_koulutuksen_lisakysymys").
        map(s => Try(s.toBoolean).getOrElse(false))
      val harkinnanvaraisuusperuste: Option[String] = toiveet.get("preference" + t._1 + "-discretionary-follow-up").flatMap(s => s match {
        case "oppimisvaikudet" => Some("1")
        case "sosiaalisetsyyt" => Some("2")
        case "todistustenvertailuvaikeudet" => Some("3")
        case "todistustenpuuttuminen" => Some("4")
        case _ => logger.error(s"invalid discretionary-follow-up value $s"); None
      })
      val aiempiperuminen = toiveet.get("preference" + t._1 + "_sora_oikeudenMenetys").map(s => Try(s.toBoolean).getOrElse(false))
      val terveys = toiveet.get("preference" + t._1 + "_sora_terveys").map(s => Try(s.toBoolean).getOrElse(false))
      Hakutoive(Hakukohde(koulutukset, hakukohdekoodi), kaksoistutkinto, urheilijanammatillinenkoulutus, harkinnanvaraisuusperuste, aiempiperuminen, terveys)
    })
  }

  def getValue(key: String, subKey: String, default: String = "")(implicit answers: Option[Map[String, Map[String, String]]]): String = {
    (for (m <- answers; c <- m.get(key); v <- c.get(subKey)) yield v).getOrElse(default)
  }

}

case class ListHakemus(oid: String)

case class HakemusHaku(totalCount: Long, results: Seq[ListHakemus])

case class FullHakemus(oid: String, personOid: Option[String], applicationSystemId: String, answers: Option[Map[String, Map[String, String]]])
