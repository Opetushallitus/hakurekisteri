package fi.vm.sade.hakurekisteri.hakija

import fi.vm.sade.hakurekisteri.hakija.Hakuehto.Hakuehto
import fi.vm.sade.hakurekisteri.hakija.Tyyppi.Tyyppi
import fi.vm.sade.hakurekisteri.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.rest.support.{Kausi, SpringSecuritySupport, HakurekisteriJsonSupport}
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.{Swagger, SwaggerEngine}
import org.scalatra._
import scala.concurrent.ExecutionContext
import _root_.akka.actor.{ActorRef, ActorSystem}
import _root_.akka.pattern.ask
import _root_.akka.util.Timeout
import java.util.concurrent.TimeUnit
import scala.util.Try
import javax.servlet.http.HttpServletResponse
import scala.xml._
import fi.vm.sade.hakurekisteri.opiskelija.Opiskelija
import fi.vm.sade.hakurekisteri.suoritus.Suoritus
import fi.vm.sade.hakurekisteri.suoritus.yksilollistaminen._
import org.joda.time.{DateTimeFieldType, LocalDate}
import scala.Some
import fi.vm.sade.hakurekisteri.rest.support.User
import scala.concurrent.duration.Duration


object Hakuehto extends Enumeration {
  type Hakuehto = Value
  val Kaikki, Hyväksytyt, Vastaanottaneet = Value
}

object Tyyppi extends Enumeration {
  type Tyyppi = Value
  val Xml, Excel, Json = Value
}

case class HakijaQuery(haku: Option[String], organisaatio: Option[String], hakukohdekoodi: Option[String], hakuehto: Hakuehto, user: Option[User])

object HakijaQuery {
  def apply(params: Map[String,String], user: Option[User]): HakijaQuery = HakijaQuery(
    params.get("haku"),
    params.get("organisaatio"),
    params.get("hakukohdekoodi"),
    Try(Hakuehto.withName(params("hakuehto"))).recover{ case _ => Hakuehto.Kaikki}.get,
    user)
}

class HakijaResource(hakijaActor: ActorRef)(implicit system: ActorSystem, sw: Swagger) extends HakuJaValintarekisteriStack with HakijaSwaggerApi with HakurekisteriJsonSupport with JacksonJsonSupport with FutureSupport with CorsSupport with SpringSecuritySupport {
  override protected implicit def executor: ExecutionContext = system.dispatcher
  override protected def applicationDescription: String = "Hakeneiden ja valittujen rajapinta."
  override protected implicit def swagger: SwaggerEngine[_] = sw

  options("/*") {
    response.setHeader("Access-Control-Allow-Headers", request.getHeader("Access-Control-Request-Headers"))
  }

  addMimeMapping("application/octet-stream", "binary")

  def getContentType(t: Tyyppi): String = t match {
    case Tyyppi.Json => formats("json")
    case Tyyppi.Xml => formats("xml")
    case Tyyppi.Excel => formats("binary")
  }

  def getFileExtension(t: Tyyppi): String = t match {
    case Tyyppi.Json => "json"
    case Tyyppi.Xml => "xml"
    case Tyyppi.Excel => "xls"
  }

  def setContentDisposition(t: Tyyppi, response: HttpServletResponse, filename: String) {
    response.setHeader("Content-Disposition", "attachment;filename=%s.%s".format(filename, getFileExtension(t)))
    response.addCookie(Cookie("fileDownload", "true")(CookieOptions(path = "/")))
  }

  override protected def renderPipeline: RenderPipeline = renderCustom orElse super.renderPipeline
  private def renderCustom: RenderPipeline = {
    case hakijat: XMLHakijat if responseFormat == "xml" => XML.write(response.writer, Utility.trim(hakijat.toXml), response.characterEncoding.get, xmlDecl = true, doctype = null)
    case hakijat: XMLHakijat if responseFormat == "binary" => ExcelUtil.write(response.outputStream, hakijat)
  }

  get("/", operation(query)) {
    val q = HakijaQuery(params, currentUser)
    logger.info("Query: " + q)

    new AsyncResult() {
      override implicit def timeout = Duration(180, TimeUnit.SECONDS)
      implicit val defaultTimeout = Timeout(90, TimeUnit.SECONDS)
      import scala.concurrent.future
      val hakuResult = Try(hakijaActor ? q).get
      val is = hakuResult.flatMap((result) => future {
        val tyyppi = Try(Tyyppi.withName(params("tyyppi"))).getOrElse(Tyyppi.Json)
        contentType = getContentType(tyyppi)
        if (Try(params("tiedosto").toBoolean).getOrElse(false)) setContentDisposition(tyyppi, response, "hakijat")
        result
      })
    }
  }

  error {
    case t: Throwable =>
      logger.error("error in service", t)
      response.sendError(500, t.getMessage)
  }
}

object XMLUtil {
  def toBooleanX(b: Boolean): String = if (b) "X" else ""
  def toBoolean10(b: Boolean): String = if (b) "1" else "0"
}

import XMLUtil._

case class XMLHakutoive(hakujno: Short, oppilaitos: String, opetuspiste: Option[String], opetuspisteennimi: Option[String], koulutus: String,
                     harkinnanvaraisuusperuste: Option[String], urheilijanammatillinenkoulutus: Option[Boolean], yhteispisteet: Option[BigDecimal],
                     valinta: Option[String], vastaanotto: Option[String], lasnaolo: Option[String], terveys: Option[Boolean], aiempiperuminen: Option[Boolean],
                     kaksoistutkinto: Option[Boolean]) {
  def toXml: Node = {
    <Hakutoive>
      <Hakujno>{hakujno}</Hakujno>
      <Oppilaitos>{oppilaitos}</Oppilaitos>
      {if (opetuspiste.isDefined) <Opetuspiste>{opetuspiste.get}</Opetuspiste>}
      {if (opetuspisteennimi.isDefined) <Opetuspisteennimi>{opetuspisteennimi.get}</Opetuspisteennimi>}
      <Koulutus>{koulutus}</Koulutus>
      {if (harkinnanvaraisuusperuste.isDefined) <Harkinnanvaraisuusperuste>{harkinnanvaraisuusperuste.get}</Harkinnanvaraisuusperuste>}
      {if (urheilijanammatillinenkoulutus.isDefined) <Urheilijanammatillinenkoulutus>{toBoolean10(urheilijanammatillinenkoulutus.get)}</Urheilijanammatillinenkoulutus>}
      {if (yhteispisteet.isDefined) <Yhteispisteet>{yhteispisteet.get}</Yhteispisteet>}
      {if (valinta.isDefined) <Valinta>{valinta.get}</Valinta>}
      {if (vastaanotto.isDefined) <Vastaanotto>{vastaanotto.get}</Vastaanotto>}
      {if (lasnaolo.isDefined) <Lasnaolo>{lasnaolo.get}</Lasnaolo>}
      {if (terveys.isDefined) <Terveys>{toBooleanX(terveys.get)}</Terveys>}
      {if (aiempiperuminen.isDefined) <Aiempiperuminen>{toBooleanX(aiempiperuminen.get)}</Aiempiperuminen>}
      {if (kaksoistutkinto.isDefined) <Kaksoistutkinto>{toBooleanX(kaksoistutkinto.get)}</Kaksoistutkinto>}
    </Hakutoive>
  }
}

object XMLHakutoive {
  def apply(ht: Hakutoive, jno: Integer, o: Organisaatio, k: String): XMLHakutoive =
    XMLHakutoive((jno + 1).toShort, k, o.toimipistekoodi, o.nimi.get("fi").orElse(o.nimi.get("sv")),
                 ht.hakukohde.hakukohdekoodi, None, None, None, None, None, None, None, None, Some(ht.kaksoistutkinto))
}

case class XMLHakemus(vuosi: String, kausi: String, hakemusnumero: String, lahtokoulu: Option[String], lahtokoulunnimi: Option[String], luokka: Option[String],
                   luokkataso: Option[String], pohjakoulutus: String, todistusvuosi: Option[String], julkaisulupa: Option[Boolean], yhteisetaineet: Option[BigDecimal],
                   lukiontasapisteet: Option[BigDecimal], lisapistekoulutus: Option[String], yleinenkoulumenestys: Option[BigDecimal],
                   painotettavataineet: Option[BigDecimal], hakutoiveet: Seq[XMLHakutoive]) {
  def toXml: Node = {
    <Hakemus>
      <Vuosi>{vuosi}</Vuosi>
      <Kausi>{kausi}</Kausi>
      <Hakemusnumero>{hakemusnumero}</Hakemusnumero>
      {if (lahtokoulu.isDefined) <Lahtokoulu>{lahtokoulu.get}</Lahtokoulu>}
      {if (lahtokoulunnimi.isDefined) <Lahtokoulunnimi>{lahtokoulunnimi.get}</Lahtokoulunnimi>}
      {if (luokka.isDefined) <Luokka>{luokka.get}</Luokka>}
      {if (luokkataso.isDefined) <Luokkataso>{luokkataso.get}</Luokkataso>}
      <Pohjakoulutus>{pohjakoulutus}</Pohjakoulutus>
      {if (todistusvuosi.isDefined) <Todistusvuosi>{todistusvuosi.get}</Todistusvuosi>}
      {if (julkaisulupa.isDefined) <Julkaisulupa>{toBooleanX(julkaisulupa.get)}</Julkaisulupa>}
      {if (yhteisetaineet.isDefined) <Yhteisetaineet>{yhteisetaineet.get}</Yhteisetaineet>}
      {if (lukiontasapisteet.isDefined) <Lukiontasapisteet>{lukiontasapisteet.get}</Lukiontasapisteet>}
      {if (lisapistekoulutus.isDefined) <Lisapistekoulutus>{lisapistekoulutus.get}</Lisapistekoulutus>}
      {if (yleinenkoulumenestys.isDefined) <Yleinenkoulumenestys>{yleinenkoulumenestys.get}</Yleinenkoulumenestys>}
      {if (painotettavataineet.isDefined) <Painotettavataineet>{painotettavataineet.get}</Painotettavataineet>}
      <Hakutoiveet>
        {hakutoiveet.map(_.toXml)}
      </Hakutoiveet>
    </Hakemus>
  }
}

object XMLHakemus {
  def resolvePohjakoulutus(suoritus: Option[Suoritus]): String = suoritus match {
    case Some(s) =>
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
    case None => "7"
  }

  def getRelevantSuoritus(suoritukset:Seq[Suoritus]) = {
    suoritukset.map(s => (s, resolvePohjakoulutus(Some(s)).toInt)).sortBy(_._2).map(_._1).headOption
  }

  def resolveYear(suoritus:Suoritus) = suoritus match {
    case Suoritus("ulkomainen", _,  _, _, _, _, _) => None
    case Suoritus(_, _, _,date, _, _, _)  => Some(date.getYear.toString)
  }

  def apply(hakija: Hakija, opiskelutieto: Option[Opiskelija], lahtokoulu: Option[Organisaatio], toiveet: Seq[XMLHakutoive]): XMLHakemus =
    XMLHakemus(vuosi = Try(hakija.hakemus.hakutoiveet.head.hakukohde.koulutukset.head.alkamisvuosi).getOrElse("" + new LocalDate().get(DateTimeFieldType.year())), // FIXME poista oletusarvo
      kausi = if (Try(hakija.hakemus.hakutoiveet.head.hakukohde.koulutukset.head.alkamiskausi).getOrElse(Kausi.Syksy) == Kausi.Kevät) "K" else "S", // FIXME poista oletusarvo
      hakemusnumero = hakija.hakemus.hakemusnumero,
      lahtokoulu = lahtokoulu.flatMap(o => o.oppilaitosKoodi),
      lahtokoulunnimi = lahtokoulu.flatMap(o => o.nimi.get("fi")),
      luokka = opiskelutieto.map(_.luokka),
      luokkataso = opiskelutieto.map(_.luokkataso),
      pohjakoulutus = resolvePohjakoulutus(getRelevantSuoritus(hakija.suoritukset)),
      todistusvuosi = getRelevantSuoritus(hakija.suoritukset).flatMap(resolveYear),
      julkaisulupa = Some(false),
      yhteisetaineet = None,
      lukiontasapisteet = None,
      lisapistekoulutus = None,
      yleinenkoulumenestys = None,
      painotettavataineet = None,
      hakutoiveet = toiveet)
}

case class XMLHakija(hetu: String, oppijanumero: String, sukunimi: String, etunimet: String, kutsumanimi: Option[String], lahiosoite: String,
                  postinumero: String, maa: String, kansalaisuus: String, matkapuhelin: Option[String], muupuhelin: Option[String], sahkoposti: Option[String],
                  kotikunta: Option[String], sukupuoli: String, aidinkieli: String, koulutusmarkkinointilupa: Boolean, hakemus: XMLHakemus) {
  def toXml: Node = {
    <Hakija>
      <Hetu>{hetu}</Hetu>
      <Oppijanumero>{oppijanumero}</Oppijanumero>
      <Sukunimi>{sukunimi}</Sukunimi>
      <Etunimet>{etunimet}</Etunimet>
      {if (kutsumanimi.isDefined) <Kutsumanimi>{kutsumanimi.get}</Kutsumanimi>}
      <Lahiosoite>{lahiosoite}</Lahiosoite>
      <Postinumero>{postinumero}</Postinumero>
      <Maa>{maa}</Maa>
      <Kansalaisuus>{kansalaisuus}</Kansalaisuus>
      {if (matkapuhelin.isDefined) <Matkapuhelin>{matkapuhelin.get}</Matkapuhelin>}
      {if (muupuhelin.isDefined) <Muupuhelin>{muupuhelin.get}</Muupuhelin>}
      {if (sahkoposti.isDefined) <Sahkoposti>{sahkoposti.get}</Sahkoposti>}
      {if (kotikunta.isDefined) <Kotikunta>{kotikunta.get}</Kotikunta>}
      <Sukupuoli>{sukupuoli}</Sukupuoli>
      <Aidinkieli>{aidinkieli}</Aidinkieli>
      <Koulutusmarkkinointilupa>{toBooleanX(koulutusmarkkinointilupa)}</Koulutusmarkkinointilupa>
      {hakemus.toXml}
    </Hakija>
  }
}

object XMLHakija {
  def apply(hakija: Hakija, yhteystiedot: Map[String, String], maa: String, kansalaisuus: String, hakemus: XMLHakemus): XMLHakija =
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
}

case class XMLHakijat(hakijat: Seq[XMLHakija]) {
  def toXml: Node = {
    <Hakijat xmlns="http://service.henkilo.sade.vm.fi/types/perusopetus/hakijat"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             xsi:schemaLocation="http://service.henkilo.sade.vm.fi/types/perusopetus/hakijat hakijat.xsd">
      {hakijat.map(_.toXml)}
    </Hakijat>
  }
}


