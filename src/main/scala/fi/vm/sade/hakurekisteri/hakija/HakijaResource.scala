package fi.vm.sade.hakurekisteri.hakija

import fi.vm.sade.hakurekisteri.hakija.Hakuehto.Hakuehto
import fi.vm.sade.hakurekisteri.hakija.Tyyppi.Tyyppi
import fi.vm.sade.hakurekisteri.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.rest.support.{User, SpringSecuritySupport, Kausi, HakurekisteriJsonSupport}
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.{Swagger, SwaggerEngine, SwaggerSupport}
import org.scalatra.{RenderPipeline, AsyncResult, CorsSupport, FutureSupport}
import scala.concurrent.{Future, ExecutionContext}
import _root_.akka.actor.{Actor, ActorRef, ActorSystem}
import _root_.akka.pattern.ask
import akka.util.Timeout
import java.util.concurrent.TimeUnit
import fi.vm.sade.hakurekisteri.henkilo._
import scala.util.Try
import fi.vm.sade.hakurekisteri.suoritus.Suoritus
import fi.vm.sade.hakurekisteri.opiskelija.Opiskelija
import org.joda.time.{LocalDate, DateTime}
import scala.Some
import fi.vm.sade.hakurekisteri.suoritus.Komoto
import fi.vm.sade.hakurekisteri.henkilo.Yhteystiedot
import fi.vm.sade.hakurekisteri.henkilo.YhteystiedotRyhma
import akka.event.Logging
import javax.servlet.http.HttpServletResponse
import scala.xml.{Elem, XML}
import org.json4s.Xml._
import fi.vm.sade.hakurekisteri.henkilo.Kansalaisuus
import scala.Some
import fi.vm.sade.hakurekisteri.hakija.Organisaatio
import fi.vm.sade.hakurekisteri.hakija.XMLHakija
import fi.vm.sade.hakurekisteri.hakija.FullHakemus
import fi.vm.sade.hakurekisteri.hakija.XMLHakutoive
import fi.vm.sade.hakurekisteri.henkilo.Kieli
import fi.vm.sade.hakurekisteri.rest.support.User
import fi.vm.sade.hakurekisteri.suoritus.Komoto
import fi.vm.sade.hakurekisteri.hakija.XMLHakijat
import fi.vm.sade.hakurekisteri.henkilo.Yhteystiedot
import fi.vm.sade.hakurekisteri.hakija.XMLHakemus
import fi.vm.sade.hakurekisteri.henkilo.YhteystiedotRyhma
import fi.vm.sade.hakurekisteri.hakija.HakijaQuery

object Hakuehto extends Enumeration {
  type Hakuehto = Value
  val Kaikki, Hyväksytyt, Vastaanottaneet = Value
}

// TODO tyyppimuunnin, joka muuntaa oletusmuodon (JSON) XML- tai Excel-muotoon
object Tyyppi extends Enumeration {
  type Tyyppi = Value
  val Xml, Excel, Json = Value
}

case class HakijaQuery(haku: Option[String], organisaatio: Option[String], hakukohdekoodi: Option[String], hakuehto: Hakuehto, tyyppi: Tyyppi, tiedosto: Option[Boolean], user: Option[User])


class HakijaResource(hakijaActor: ActorRef)(implicit system: ActorSystem, sw: Swagger) extends HakuJaValintarekisteriStack with HakurekisteriJsonSupport with JacksonJsonSupport with SwaggerSupport with FutureSupport with CorsSupport with SpringSecuritySupport {
  override protected implicit def executor: ExecutionContext = system.dispatcher
  implicit val defaultTimeout = Timeout(60, TimeUnit.SECONDS)
  override protected def applicationDescription: String = "Hakeneiden ja valittujen rajapinta."
  override protected implicit def swagger: SwaggerEngine[_] = sw

  options("/*") {
    response.setHeader("Access-Control-Allow-Headers", request.getHeader("Access-Control-Request-Headers"))
  }

  def getContentType(t: Tyyppi): String = t match {
    case Tyyppi.Json => formats("json")
    case Tyyppi.Xml => formats("xml")
    case Tyyppi.Excel => "application/vnd.ms-excel"
  }

  def getFileExtension(t: Tyyppi): String = t match {
    case Tyyppi.Json => "json"
    case Tyyppi.Xml => "xml"
    case Tyyppi.Excel => "xls"
  }

  def setContentDisposition(q: HakijaQuery, response: HttpServletResponse): Unit = q.tiedosto.map(returnAsFile => {
    if (returnAsFile) response.setHeader("Content-Disposition", "attachment;filename=hakijat." + getFileExtension(q.tyyppi))
  })

  def containsValue(e: Enumeration, s: String): Boolean = {
    Try { e.withName(s); true }.getOrElse(false)
  }

  override protected def renderPipeline: RenderPipeline = renderCustom orElse super.renderPipeline

  private def renderCustom: RenderPipeline = {
    case hakijat: XMLHakijat if responseFormat == "xml" => {
      logger.debug("hakijat: " + XMLUtil.toXml(hakijat).toString)
      XML.write(response.writer, XMLUtil.toXml(hakijat), response.characterEncoding.get, xmlDecl = true, doctype = null)
    }
    case hakijat: XMLHakijat if response.contentType == "application/vnd.ms-excel" => {
      logger.debug("hakijat to Excel: " + hakijat)
      ExcelUtil.writeHakijatAsExcel(hakijat, response.getOutputStream)
    }
  }

  get("/") {
    val hakuehto: String = params.getOrElse("hakuehto", "")
    val tyyppi: String = params.getOrElse("tyyppi", "")
    if (hakuehto == "" || containsValue(Hakuehto, hakuehto) == false || tyyppi == "" || containsValue(Tyyppi, tyyppi) == false) {
      logger.warn("invalid query params: hakuehto=" + hakuehto + ", tyyppi=" + tyyppi)
      response.sendError(400, "hakuehto tai tyyppi puuttuu tai arvo on virheellinen")
    } else {
      val q = HakijaQuery(
        params.get("haku"),
        params.get("organisaatio"),
        params.get("hakukohdekoodi"),
        Hakuehto.withName(hakuehto),
        Tyyppi.withName(tyyppi),
        params.get("tiedosto").map(_.toBoolean),
        currentUser)

      logger.info("Query: " + q)

      contentType = getContentType(q.tyyppi)
      setContentDisposition(q, response)

      new AsyncResult() {
        val is = hakijaActor ? q
        is.onFailure {
          case t: Throwable => {
            logger.error("error in service", t)
            response.sendError(500, t.getMessage)
          }
        }
      }
    }
  }

}

import akka.pattern.pipe

class HakijaActor(hakupalvelu: Hakupalvelu, organisaatiopalvelu: Organisaatiopalvelu) extends Actor {

  implicit val executionContext: ExecutionContext = context.dispatcher

  val log = Logging(context.system, this)

  def receive = {
    case q: HakijaQuery => {
      XMLQuery(q) pipeTo sender
    }
  }

  case class Hakukohde(koulutukset: Set[Komoto], hakukohdekoodi: String)

  case class Hakutoive(hakukohde: Hakukohde, kaksoistutkinto: Boolean)

  case class Hakemus(hakutoiveet: Seq[Hakutoive], hakemusnumero: String)

  case class Hakija(henkilo: Henkilo, suoritukset: Seq[Suoritus], opiskeluhistoria: Seq[Opiskelija], hakemus: Hakemus)

  implicit def yhteystietoryhmatToMap(yhteystiedot: Seq[YhteystiedotRyhma]): Map[(String, String), Seq[Yhteystiedot]] = {
    yhteystiedot.map((y) => (y.ryhmaAlkuperaTieto, y.ryhmaKuvaus) -> y.yhteystiedot).toMap
  }

  implicit def yhteystiedotToMap(yhteystiedot: Seq[Yhteystiedot]): Map[String, String] = {
    yhteystiedot.map((y) => y.yhteystietoTyyppi -> y.yhteystietoArvo).toMap
  }

  def resolveOppilaitosKoodi(o:Organisaatio): Future[Option[String]] =  o.oppilaitosKoodi match {
    case None => findOppilaitoskoodi(o.parentOid)
    case Some(k) => Future(Some(k))
  }


  def findOppilaitoskoodi(parentOid: Option[String]): Future[Option[String]] = parentOid match {
    case None => log.debug("no parentOid"); Future(None)
    case Some(oid) => log.debug("parentOid: " + oid); organisaatiopalvelu.get(oid).flatMap(_.map(resolveOppilaitosKoodi).getOrElse(Future(None)))
  }

  @Deprecated // TODO mäppää puuttuvat tiedot
  def getXmlHakutoiveet(hakija: Hakija): Future[Seq[XMLHakutoive]] = {
    log.debug("get xml hakutoiveet for: " + hakija.henkilo.oidHenkilo)
    val futures = hakija.hakemus.hakutoiveet.zipWithIndex.map(ht => {
      findOrgData(ht._1.hakukohde.koulutukset.head.tarjoaja).map(option => option.map((t) => {
        val o = t._1
        val k = t._2
        XMLHakutoive(
          hakujno = (ht._2 + 1).toShort,
          oppilaitos = k,
          opetuspiste = o.toimipistekoodi,
          opetuspisteennimi = o.nimi.get("fi").orElse(o.nimi.get("sv")),
          koulutus = ht._1.hakukohde.hakukohdekoodi,
          harkinnanvaraisuusperuste = None,
          urheilijanammatillinenkoulutus = None,
          yhteispisteet = None,
          valinta = None,
          vastaanotto = None,
          lasnaolo = None,
          terveys = None,
          aiempiperuminen = None,
          kaksoistutkinto = None
        )
      }))
    }).toSeq
    Future.sequence(futures).map(_.flatten)
  }

  def extractOption(t: (Option[Organisaatio], Option[String])): Option[(Organisaatio, String)] = t._1 match {
    case None => None
    case Some(o) => Some((o, t._2.get))
  }

  def findOrgData(tarjoaja: String): Future[Option[(Organisaatio,String)]] = {
    log.debug("find org data for: " + tarjoaja)
    organisaatiopalvelu.get(tarjoaja).flatMap((o) => findOppilaitoskoodi(o.map(_.oid)).map(k => extractOption(o,k)))
  }

  import fi.vm.sade.hakurekisteri.suoritus.yksilollistaminen._

  def resolvePohjakoulutus(suoritus: Option[Suoritus]): String = suoritus match {
    case Some(s) => {
      s.komo match {
        case "ulkomainen" => "0"
        case "peruskoulu" => s.yksilollistaminen match {
          case Ei => "1"
          case Osittain => "2"
          case Alueittain => "3"
          case Kokonaan => "6"
        }
        case "lukio" => "9"
      }
    }
    case None => "7"
  }

  @Deprecated // TODO mäppää puuttuvat tiedot
  def getXmlHakemus(hakija: Hakija, opiskelutieto: Option[Opiskelija], lahtokoulu: Option[Organisaatio]): Future[Option[XMLHakemus]] = {
    val ht: Future[Seq[XMLHakutoive]] = getXmlHakutoiveet(hakija)
    ht.map(toiveet => {

      Try(hakija.hakemus).map((hakemus: Hakemus) =>
        XMLHakemus(
          vuosi = Try(hakemus.hakutoiveet.head.hakukohde.koulutukset.head.alkamisvuosi).get,
          kausi = if (Try(hakemus.hakutoiveet.head.hakukohde.koulutukset.head.alkamiskausi).get == Kausi.Kevät) "K" else "S",
          hakemusnumero = hakemus.hakemusnumero,
          lahtokoulu = lahtokoulu.flatMap(o => o.oppilaitosKoodi),
          lahtokoulunnimi = lahtokoulu.flatMap(o => o.nimi.get("fi")),
          luokka = opiskelutieto.map(_.luokka),
          luokkataso = opiskelutieto.map(_.luokkataso),
          pohjakoulutus = resolvePohjakoulutus(Try(hakija.suoritukset.head).toOption),
          todistusvuosi = Some("2014"),
          julkaisulupa = Some(false),
          yhteisetaineet = None,
          lukiontasapisteet = None,
          lisapistekoulutus = None,
          yleinenkoulumenestys = None,
          painotettavataineet = None,
          hakutoiveet = toiveet)
      ).toOption
    })
  }

  def getXmlHakemus(hakija: Hakija): Future[Option[XMLHakemus]] = {
    hakija.opiskeluhistoria.size match {
      case 0 => getXmlHakemus(hakija, None, None)
      case _ => organisaatiopalvelu.get(hakija.opiskeluhistoria.head.oppilaitosOid).flatMap((o: Option[Organisaatio]) => getXmlHakemus(hakija, Some(hakija.opiskeluhistoria.head), o))
    }
  }

  @Deprecated // TODO ratkaise kaksoiskansalaisuus
  def hakija2XMLHakija(hakija: Hakija): Future[Option[XMLHakija]] = {
    getXmlHakemus(hakija).map((hakemus) => {
      log.debug("map hakemus henkilolle: " + hakija.henkilo.oidHenkilo)
      val yhteystiedot: Seq[Yhteystiedot] = hakija.henkilo.yhteystiedotRyhma.getOrElse(("hakemus", "yhteystietotyyppi1"), Seq())
      hakemus.map(hakemus =>
        XMLHakija(
          hakija.henkilo.hetu,
          hakija.henkilo.oidHenkilo,
          hakija.henkilo.sukunimi,
          hakija.henkilo.etunimet,
          Some(hakija.henkilo.kutsumanimi),
          yhteystiedot.getOrElse("YHTEYSTIETO_KATUOSOITE",""),
          yhteystiedot.getOrElse("YHTEYSTIETO_POSTINUMERO", ""),
          yhteystiedot.getOrElse("YHTEYSTIETO_MAA", ""),
          Try(hakija.henkilo.kansalaisuus.head).map(k => k.kansalaisuusKoodi).recover{case _:Throwable => ""}.get,
          yhteystiedot.get("YHTEYSTIETO_MATKAPUHELIN"),
          yhteystiedot.get("YHTEYSTIETO_PUHELINNUMERO"),
          yhteystiedot.get("YHTEYSTIETO_SAHKOPOSTI"),
          yhteystiedot.get("YHTEYSTIETO_KAUPUNKI"),
          if (hakija.henkilo.sukupuoli == "MIES") "1" else "2", hakija.henkilo.asiointiKieli.kieliKoodi,
          hakija.henkilo.markkinointilupa.getOrElse(false),
          hakemus
        )
      )
    })
  }

  def getHakija(hakemus: FullHakemus): Hakija = {
    log.debug("getting hakija from full hakemus: " + hakemus.oid)
    val lahtokoulu: Option[String] = hakemus.answers.flatMap(_.koulutustausta.lahtokoulu)
    val a = hakemus.answers
    val h = a.flatMap(_.henkilotiedot)
    val hak = Hakija(
      Henkilo(
        yhteystiedotRyhma = Seq(),
        yksiloity = false,
        sukunimi = h.map(_.Sukunimi).getOrElse(""),
        etunimet = h.map(_.Etunimet).getOrElse(""),
        kutsumanimi = h.map(_.Kutsumanimi).getOrElse(""),
        kielisyys = Seq(),
        yksilointitieto = None,
        henkiloTyyppi = "OPPIJA",
        oidHenkilo = hakemus.personOid,
        duplicate = false,
        oppijanumero = hakemus.personOid,
        kayttajatiedot = None,
        kansalaisuus = Seq(Kansalaisuus(h.map(_.kansalaisuus).getOrElse(""))),
        passinnumero = "",
        asiointiKieli = Kieli("FI", "FI"),
        passivoitu = false,
        eiSuomalaistaHetua = h.flatMap(_.onkoSinullaSuomalainenHetu).getOrElse(false),
        sukupuoli = h.map(_.sukupuoli).getOrElse(""),
        hetu = h.map(_.Henkilotunnus).getOrElse(""),
        syntymaaika = h.map(_.syntymaaika).getOrElse(""),
        turvakielto = false,
        markkinointilupa = hakemus.answers.flatMap(_.lisatiedot.map(_.lupaMarkkinointi))
      ),
      Seq(Suoritus(
        komo = "peruskoulu",
        myontaja = lahtokoulu.getOrElse(""),
        tila = "KESKEN",
        valmistuminen = LocalDate.now,
        henkiloOid = hakemus.personOid,
        yksilollistaminen = Ei,
        suoritusKieli = hakemus.answers.map(_.koulutustausta.perusopetuksen_kieli).getOrElse("FI")
      )),
      lahtokoulu match {
        case Some(oid) => Seq(Opiskelija(
          oppilaitosOid = lahtokoulu.get,
          henkiloOid = hakemus.personOid,
          luokkataso = hakemus.answers.map(_.koulutustausta.luokkataso).getOrElse(""),
          luokka = hakemus.answers.flatMap(_.koulutustausta.lahtoluokka).getOrElse(""),
          alkuPaiva = DateTime.now.minus(org.joda.time.Duration.standardDays(1)),
          loppuPaiva = None
        ))
        case _ => Seq()
      },
      a.flatMap(_.hakutoiveet).map(toiveet => {
        val hakutoiveet = convertToiveet(toiveet)
        Hakemus(hakutoiveet, hakemus.oid)
      }).getOrElse(Hakemus(Seq(), hakemus.oid))
    )
    log.debug("hakija: " + hak)
    hak
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
      Hakutoive(Hakukohde(koulutukset, hakukohdekoodi), toiveet.get("preference" + t._1 + "-Koulutus-id-kaksoistutkinto").map(_.toBoolean).getOrElse(false))
    })
  }

  def selectHakijat(q: HakijaQuery): Future[Seq[Hakija]] = {
    val y: Future[Future[Seq[Hakija]]] = findHakemukset(q).map(hakemukset => {
      val kk: Seq[Future[Option[Hakija]]] = hakemukset.map(sh => getHakemus(sh.oid, q.user).map((fh: Option[FullHakemus]) => fh.map(getHakija(_))))
      val f: Future[Seq[Hakija]] = Future.sequence(kk).map((s: Seq[Option[Hakija]]) => s.flatten)
      f.onComplete(res => {log.debug("hakijat result: " + res); if (res.isFailure) res.failed.get.printStackTrace()})
      f
    })
    y.flatMap(f => f.map(g => g))
  }

  def XMLQuery(q: HakijaQuery): Future[XMLHakijat] = {
    log.debug("XMLQuery: " + q)
    selectHakijat(q).map(_.map(hakija2XMLHakija)).flatMap(Future.sequence(_).map(hakijat => XMLHakijat(hakijat.flatten)))
  }

  def findHakemukset = hakupalvelu.find(_)

  def getHakemus = hakupalvelu.get(_, _)

}

object XMLUtil {
  def toBooleanX(b: Boolean): String = if (b) "X" else ""
  def toBoolean10(b: Boolean): String = if (b) "1" else "0"
  def toXml(hakijat: XMLHakijat): Elem = {
<Hakijat xmlns="http://service.henkilo.sade.vm.fi/types/perusopetus/hakijat"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://service.henkilo.sade.vm.fi/types/perusopetus/hakijat hakijat.xsd">
  {hakijat.hakijat.map((hakija: XMLHakija) => {
  <Hakija>
    <Hetu>{hakija.hetu}</Hetu>
    <Oppijanumero>{hakija.oppijanumero}</Oppijanumero>
    <Sukunimi>{hakija.sukunimi}</Sukunimi>
    <Etunimet>{hakija.etunimet}</Etunimet>
    <Kutsumanimi>{hakija.kutsumanimi.getOrElse("")}</Kutsumanimi>
    <Lahiosoite>{hakija.lahiosoite}</Lahiosoite>
    <Postinumero>{hakija.postinumero}</Postinumero>
    <Maa>{hakija.maa}</Maa>
    <Kansalaisuus>{hakija.kansalaisuus}</Kansalaisuus>
    <Matkapuhelin>{hakija.matkapuhelin.getOrElse("")}</Matkapuhelin>
    <Muupuhelin>{hakija.muupuhelin.getOrElse("")}</Muupuhelin>
    <Sahkoposti>{hakija.sahkoposti.getOrElse("")}</Sahkoposti>
    <Kotikunta>{hakija.kotikunta.getOrElse("")}</Kotikunta>
    <Sukupuoli>{hakija.sukupuoli}</Sukupuoli>
    <Aidinkieli>{hakija.aidinkieli}</Aidinkieli>
    <Koulutusmarkkinointilupa>{toBooleanX(hakija.koulutusmarkkinointilupa)}</Koulutusmarkkinointilupa>
    <Hakemus>
      <Vuosi>{hakija.hakemus.vuosi}</Vuosi>
      <Kausi>{hakija.hakemus.kausi}</Kausi>
      <Hakemusnumero>{hakija.hakemus.hakemusnumero}</Hakemusnumero>
      <Lahtokoulu>{hakija.hakemus.lahtokoulu.getOrElse("")}</Lahtokoulu>
      <Lahtokoulunnimi>{hakija.hakemus.lahtokoulunnimi.getOrElse("")}</Lahtokoulunnimi>
      <Luokka>{hakija.hakemus.luokka.getOrElse("")}</Luokka>
      <Luokkataso>{hakija.hakemus.luokkataso.getOrElse("")}</Luokkataso>
      <Pohjakoulutus>{hakija.hakemus.pohjakoulutus}</Pohjakoulutus>
      <Todistusvuosi>{hakija.hakemus.todistusvuosi.getOrElse("")}</Todistusvuosi>
      <Julkaisulupa>{toBooleanX(hakija.hakemus.julkaisulupa.getOrElse(false))}</Julkaisulupa>
      <Yhteisetaineet>{hakija.hakemus.yhteisetaineet.getOrElse(null)}</Yhteisetaineet>
      <Lukiontasapisteet>{hakija.hakemus.lukiontasapisteet.getOrElse(null)}</Lukiontasapisteet>
      <Lisapistekoulutus>{hakija.hakemus.lisapistekoulutus.getOrElse("")}</Lisapistekoulutus>
      <Yleinenkoulumenestys>{hakija.hakemus.yleinenkoulumenestys.getOrElse(null)}</Yleinenkoulumenestys>
      <Painotettavataineet>{hakija.hakemus.painotettavataineet.getOrElse(null)}</Painotettavataineet>
      <Hakutoiveet>
        {hakija.hakemus.hakutoiveet.map((hakutoive: XMLHakutoive) => {
        <Hakutoive>
          <Hakujno>{hakutoive.hakujno}</Hakujno>
          <Oppilaitos>{hakutoive.oppilaitos}</Oppilaitos>
          <Opetuspiste>{hakutoive.opetuspiste.getOrElse("")}</Opetuspiste>
          <Opetuspisteennimi>{hakutoive.opetuspisteennimi.getOrElse("")}</Opetuspisteennimi>
          <Koulutus>{hakutoive.koulutus}</Koulutus>
          <Harkinnanvaraisuusperuste>{hakutoive.harkinnanvaraisuusperuste.getOrElse("")}</Harkinnanvaraisuusperuste>
          <Urheilijanammatillinenkoulutus>{toBoolean10(hakutoive.urheilijanammatillinenkoulutus.getOrElse(false))}</Urheilijanammatillinenkoulutus>
          <Yhteispisteet>{hakutoive.yhteispisteet.getOrElse(null)}</Yhteispisteet>
          <Valinta>{hakutoive.valinta.getOrElse("")}</Valinta>
          <Vastaanotto>{hakutoive.vastaanotto.getOrElse("")}</Vastaanotto>
          <Lasnaolo>{hakutoive.lasnaolo.getOrElse("")}</Lasnaolo>
          <Terveys>{toBooleanX(hakutoive.terveys.getOrElse(false))}</Terveys>
          <Aiempiperuminen>{toBooleanX(hakutoive.aiempiperuminen.getOrElse(false))}</Aiempiperuminen>
          <Kaksoistutkinto>{toBooleanX(hakutoive.kaksoistutkinto.getOrElse(false))}</Kaksoistutkinto>
        </Hakutoive>
        })}
      </Hakutoiveet>
    </Hakemus>
  </Hakija>
  })}
</Hakijat>
  }
}

case class XMLHakutoive(hakujno: Short, oppilaitos: String, opetuspiste: Option[String], opetuspisteennimi: Option[String], koulutus: String,
                     harkinnanvaraisuusperuste: Option[String], urheilijanammatillinenkoulutus: Option[Boolean], yhteispisteet: Option[BigDecimal],
                     valinta: Option[String], vastaanotto: Option[String], lasnaolo: Option[String], terveys: Option[Boolean], aiempiperuminen: Option[Boolean],
                     kaksoistutkinto: Option[Boolean])

case class XMLHakemus(vuosi: String, kausi: String, hakemusnumero: String, lahtokoulu: Option[String], lahtokoulunnimi: Option[String], luokka: Option[String],
                   luokkataso: Option[String], pohjakoulutus: String, todistusvuosi: Option[String], julkaisulupa: Option[Boolean], yhteisetaineet: Option[BigDecimal],
                   lukiontasapisteet: Option[BigDecimal], lisapistekoulutus: Option[String], yleinenkoulumenestys: Option[BigDecimal],
                   painotettavataineet: Option[BigDecimal], hakutoiveet: Seq[XMLHakutoive])

case class XMLHakija(hetu: String, oppijanumero: String, sukunimi: String, etunimet: String, kutsumanimi: Option[String], lahiosoite: String,
                  postinumero: String, maa: String, kansalaisuus: String, matkapuhelin: Option[String], muupuhelin: Option[String], sahkoposti: Option[String],
                  kotikunta: Option[String], sukupuoli: String, aidinkieli: String, koulutusmarkkinointilupa: Boolean, hakemus: XMLHakemus)

case class XMLHakijat(hakijat: Seq[XMLHakija])


