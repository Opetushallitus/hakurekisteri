package fi.vm.sade.hakurekisteri.hakija

import fi.vm.sade.hakurekisteri.hakija.Hakuehto.Hakuehto
import fi.vm.sade.hakurekisteri.hakija.Tyyppi.Tyyppi
import fi.vm.sade.hakurekisteri.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.rest.support.{SpringSecuritySupport, HakurekisteriJsonSupport}
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.{Swagger, SwaggerEngine, SwaggerSupport}
import org.scalatra.{RenderPipeline, AsyncResult, CorsSupport, FutureSupport}
import scala.concurrent.ExecutionContext
import _root_.akka.actor.{ActorRef, ActorSystem}
import _root_.akka.pattern.ask
import akka.util.Timeout
import java.util.concurrent.TimeUnit
import scala.util.Try
import fi.vm.sade.hakurekisteri.suoritus.Suoritus
import org.joda.time.DateTime
import javax.servlet.http.HttpServletResponse
import scala.xml._
import scala.xml.transform.RewriteRule
import scala.Some
import fi.vm.sade.hakurekisteri.rest.support.User
import fi.vm.sade.hakurekisteri.suoritus.Komoto
import fi.vm.sade.hakurekisteri.henkilo.Yhteystiedot

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
  implicit val defaultTimeout = Timeout(120, TimeUnit.SECONDS)
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
      logger.debug("hakijat: " + hakijat.toXml.toString)
      XML.write(response.writer, Utility.trim(hakijat.toXml), response.characterEncoding.get, xmlDecl = true, doctype = null)
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

case class XMLHakijat(hakijat: Seq[XMLHakija]) {
  def toXml: Node = {
    <Hakijat xmlns="http://service.henkilo.sade.vm.fi/types/perusopetus/hakijat"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             xsi:schemaLocation="http://service.henkilo.sade.vm.fi/types/perusopetus/hakijat hakijat.xsd">
      {hakijat.map(_.toXml)}
    </Hakijat>
  }
}


