package fi.vm.sade.hakurekisteri.hakija

import fi.vm.sade.hakurekisteri.hakija.Hakuehto.Hakuehto
import fi.vm.sade.hakurekisteri.hakija.Tyyppi.Tyyppi
import fi.vm.sade.hakurekisteri.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.rest.support.{SpringSecuritySupport, Kausi, HakurekisteriJsonSupport}
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
import akka.event.Logging
import javax.servlet.http.HttpServletResponse
import scala.xml._
import scala.xml.transform.{RuleTransformer, RewriteRule}
import fi.vm.sade.hakurekisteri.henkilo.Kansalaisuus
import scala.Some
import fi.vm.sade.hakurekisteri.henkilo.Kieli
import fi.vm.sade.hakurekisteri.rest.support.User
import fi.vm.sade.hakurekisteri.suoritus.Komoto
import fi.vm.sade.hakurekisteri.henkilo.Yhteystiedot
import fi.vm.sade.hakurekisteri.henkilo.YhteystiedotRyhma

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

  addMimeMapping("application/vnd.ms-excel", "excel")

  def getContentType(t: Tyyppi): String = t match {
    case Tyyppi.Json => formats("json")
    case Tyyppi.Xml => formats("xml")
    case Tyyppi.Excel => formats("excel")
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
    case hakijat: XMLHakijat if responseFormat == "excel" => {
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
            contentType = formats("html")
            if (response.containsHeader("Content-Disposition")) response.setHeader("Content-Disposition", "")
            response.sendError(500, t.getMessage)
          }
        }
      }
    }
  }

}

import akka.pattern.pipe

class HakijaActor(hakupalvelu: Hakupalvelu, organisaatiopalvelu: Organisaatiopalvelu, koodistopalvelu: Koodistopalvelu) extends Actor {

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

  def getMaakoodi(koodiArvo: String): Future[String] = {
    koodistopalvelu.getRinnasteinenKoodiArvo("maatjavaltiot1_" + koodiArvo.toLowerCase, "maatjavaltiot2")
  }

  @Deprecated // TODO ratkaise kaksoiskansalaisuus
  def hakija2XMLHakija(hakija: Hakija): Future[Option[XMLHakija]] = {
    getXmlHakemus(hakija).flatMap((hakemus) => {
      log.debug("map hakemus henkilolle: " + hakija.henkilo.oidHenkilo)
      val yhteystiedot: Seq[Yhteystiedot] = hakija.henkilo.yhteystiedotRyhma.getOrElse(("hakemus", "yhteystietotyyppi1"), Seq())
      hakemus.map(hakemus => {
        val maaFuture = getMaakoodi(yhteystiedot.getOrElse("YHTEYSTIETO_MAA", "FIN"))
        maaFuture.flatMap((maa) => {
          val kansalaisuusFuture = getMaakoodi(Try(hakija.henkilo.kansalaisuus.head).map(k => k.kansalaisuusKoodi).recover{case _:Throwable => "FIN"}.get)
          kansalaisuusFuture.map((kansalaisuus) => {
            XMLHakija(
              hakija.henkilo.hetu,
              hakija.henkilo.oidHenkilo,
              hakija.henkilo.sukunimi,
              hakija.henkilo.etunimet,
              Some(hakija.henkilo.kutsumanimi),
              yhteystiedot.getOrElse("YHTEYSTIETO_KATUOSOITE", ""),
              yhteystiedot.getOrElse("YHTEYSTIETO_POSTINUMERO", "00000"),
              maa,
              kansalaisuus,
              yhteystiedot.get("YHTEYSTIETO_MATKAPUHELIN"),
              yhteystiedot.get("YHTEYSTIETO_PUHELINNUMERO"),
              yhteystiedot.get("YHTEYSTIETO_SAHKOPOSTI"),
              yhteystiedot.get("YHTEYSTIETO_KAUPUNKI"),
              if (hakija.henkilo.sukupuoli == "MIES") "1" else "2", hakija.henkilo.asiointiKieli.kieliKoodi,
              hakija.henkilo.markkinointilupa.getOrElse(false),
              hakemus
            )
          })
        })
      }).map(f => f.map(Option(_))).getOrElse(Future.successful(None))
    })
  }

  def getHakija(hakemus: FullHakemus): Hakija = {
    log.debug("getting hakija from full hakemus: " + hakemus.oid)
    val lahtokoulu: Option[String] = hakemus.answers.flatMap(_.koulutustausta.lahtokoulu)
    val a = hakemus.answers
    val h = a.flatMap(_.henkilotiedot)
    val hak = Hakija(
      Henkilo(
        yhteystiedotRyhma = Seq(YhteystiedotRyhma(0, "hakemus", "yhteystietotyyppi1", true, Seq(
          Yhteystiedot(0, "YHTEYSTIETO_KATUOSOITE", h.map(_.lahiosoite).getOrElse("")),
          Yhteystiedot(1, "YHTEYSTIETO_POSTINUMERO", h.map(_.Postinumero).getOrElse("")),
          Yhteystiedot(2, "YHTEYSTIETO_MAA", h.map(_.asuinmaa).getOrElse("")),
          Yhteystiedot(3, "YHTEYSTIETO_MATKAPUHELIN", h.map(_.matkapuhelinnumero1).getOrElse("")),
          Yhteystiedot(4, "YHTEYSTIETO_SAHKOPOSTI", h.map(_.Sähköposti).getOrElse("")),
          Yhteystiedot(5, "YHTEYSTIETO_KAUPUNKI", h.map(_.kotikunta).getOrElse(""))
        ))),
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
        valmistuminen = Some(LocalDate.now),
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
    val y: Future[Future[Seq[Hakija]]] = hakupalvelu.find(q).map(hakemukset => {
      val kk: Seq[Future[Option[Hakija]]] = hakemukset.map(sh => hakupalvelu.get(sh.oid, q.user).map((fh: Option[FullHakemus]) => fh.map(getHakija(_))))
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

}

object XMLUtil {

  def toBooleanX(b: Boolean): String = if (b) "X" else ""

  def toBoolean10(b: Boolean): String = if (b) "1" else "0"

  def toXml(hakijat: XMLHakijat): Node = {
<Hakijat xmlns="http://service.henkilo.sade.vm.fi/types/perusopetus/hakijat"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://service.henkilo.sade.vm.fi/types/perusopetus/hakijat hakijat.xsd">
  {hakijat.hakijat.map((hakija: XMLHakija) => {
  <Hakija>
    <Hetu>{hakija.hetu}</Hetu>
    <Oppijanumero>{hakija.oppijanumero}</Oppijanumero>
    <Sukunimi>{hakija.sukunimi}</Sukunimi>
    <Etunimet>{hakija.etunimet}</Etunimet>
    {if (hakija.kutsumanimi.isDefined) <Kutsumanimi>{hakija.kutsumanimi.get}</Kutsumanimi>}
    <Lahiosoite>{hakija.lahiosoite}</Lahiosoite>
    <Postinumero>{hakija.postinumero}</Postinumero>
    <Maa>{hakija.maa}</Maa>
    <Kansalaisuus>{hakija.kansalaisuus}</Kansalaisuus>
    {if (hakija.matkapuhelin.isDefined) <Matkapuhelin>{hakija.matkapuhelin.get}</Matkapuhelin>}
    {if (hakija.muupuhelin.isDefined) <Muupuhelin>{hakija.muupuhelin.get}</Muupuhelin>}
    {if (hakija.sahkoposti.isDefined) <Sahkoposti>{hakija.sahkoposti.get}</Sahkoposti>}
    {if (hakija.kotikunta.isDefined) <Kotikunta>{hakija.kotikunta.get}</Kotikunta>}
    <Sukupuoli>{hakija.sukupuoli}</Sukupuoli>
    <Aidinkieli>{hakija.aidinkieli}</Aidinkieli>
    <Koulutusmarkkinointilupa>{toBooleanX(hakija.koulutusmarkkinointilupa)}</Koulutusmarkkinointilupa>
    <Hakemus>
      <Vuosi>{hakija.hakemus.vuosi}</Vuosi>
      <Kausi>{hakija.hakemus.kausi}</Kausi>
      <Hakemusnumero>{hakija.hakemus.hakemusnumero}</Hakemusnumero>
      {if (hakija.hakemus.lahtokoulu.isDefined) <Lahtokoulu>{hakija.hakemus.lahtokoulu.get}</Lahtokoulu>}
      {if (hakija.hakemus.lahtokoulunnimi.isDefined) <Lahtokoulunnimi>{hakija.hakemus.lahtokoulunnimi.get}</Lahtokoulunnimi>}
      {if (hakija.hakemus.luokka.isDefined) <Luokka>{hakija.hakemus.luokka.get}</Luokka>}
      {if (hakija.hakemus.luokkataso.isDefined) <Luokkataso>{hakija.hakemus.luokkataso.get}</Luokkataso>}
      <Pohjakoulutus>{hakija.hakemus.pohjakoulutus}</Pohjakoulutus>
      {if (hakija.hakemus.todistusvuosi.isDefined) <Todistusvuosi>{hakija.hakemus.todistusvuosi.get}</Todistusvuosi>}
      {if (hakija.hakemus.julkaisulupa.isDefined) <Julkaisulupa>{toBooleanX(hakija.hakemus.julkaisulupa.get)}</Julkaisulupa>}
      {if (hakija.hakemus.yhteisetaineet.isDefined) <Yhteisetaineet>{hakija.hakemus.yhteisetaineet.get}</Yhteisetaineet>}
      {if (hakija.hakemus.lukiontasapisteet.isDefined) <Lukiontasapisteet>{hakija.hakemus.lukiontasapisteet.get}</Lukiontasapisteet>}
      {if (hakija.hakemus.lisapistekoulutus.isDefined) <Lisapistekoulutus>{hakija.hakemus.lisapistekoulutus.get}</Lisapistekoulutus>}
      {if (hakija.hakemus.yleinenkoulumenestys.isDefined) <Yleinenkoulumenestys>{hakija.hakemus.yleinenkoulumenestys.get}</Yleinenkoulumenestys>}
      {if (hakija.hakemus.painotettavataineet.isDefined) <Painotettavataineet>{hakija.hakemus.painotettavataineet.get}</Painotettavataineet>}
      <Hakutoiveet>
        {hakija.hakemus.hakutoiveet.map((hakutoive: XMLHakutoive) => {
        <Hakutoive>
          <Hakujno>{hakutoive.hakujno}</Hakujno>
          <Oppilaitos>{hakutoive.oppilaitos}</Oppilaitos>
          {if (hakutoive.opetuspiste.isDefined) <Opetuspiste>{hakutoive.opetuspiste.get}</Opetuspiste>}
          {if (hakutoive.opetuspisteennimi.isDefined) <Opetuspisteennimi>{hakutoive.opetuspisteennimi.get}</Opetuspisteennimi>}
          <Koulutus>{hakutoive.koulutus}</Koulutus>
          {if (hakutoive.harkinnanvaraisuusperuste.isDefined) <Harkinnanvaraisuusperuste>{hakutoive.harkinnanvaraisuusperuste.get}</Harkinnanvaraisuusperuste>}
          {if (hakutoive.urheilijanammatillinenkoulutus.isDefined) <Urheilijanammatillinenkoulutus>{toBoolean10(hakutoive.urheilijanammatillinenkoulutus.get)}</Urheilijanammatillinenkoulutus>}
          {if (hakutoive.yhteispisteet.isDefined) <Yhteispisteet>{hakutoive.yhteispisteet.get}</Yhteispisteet>}
          {if (hakutoive.valinta.isDefined) <Valinta>{hakutoive.valinta.get}</Valinta>}
          {if (hakutoive.vastaanotto.isDefined) <Vastaanotto>{hakutoive.vastaanotto.get}</Vastaanotto>}
          {if (hakutoive.lasnaolo.isDefined) <Lasnaolo>{hakutoive.lasnaolo.get}</Lasnaolo>}
          {if (hakutoive.terveys.isDefined) <Terveys>{toBooleanX(hakutoive.terveys.get)}</Terveys>}
          {if (hakutoive.aiempiperuminen.isDefined) <Aiempiperuminen>{toBooleanX(hakutoive.aiempiperuminen.get)}</Aiempiperuminen>}
          {if (hakutoive.kaksoistutkinto.isDefined) <Kaksoistutkinto>{toBooleanX(hakutoive.kaksoistutkinto.get)}</Kaksoistutkinto>}
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


