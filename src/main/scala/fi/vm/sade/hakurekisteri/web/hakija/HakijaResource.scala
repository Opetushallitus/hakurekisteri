package fi.vm.sade.hakurekisteri.web.hakija

import java.io.OutputStream

import _root_.akka.actor.{ActorRef, ActorSystem}
import _root_.akka.event.{Logging, LoggingAdapter}
import _root_.akka.pattern.{AskTimeoutException, ask}
import _root_.akka.util.Timeout
import fi.vm.sade.auditlog.{Changes, Target}
import fi.vm.sade.hakurekisteri.{AuditUtil, HakijatLuku, KKHakijatLuku}
import fi.vm.sade.hakurekisteri.hakija._
import fi.vm.sade.hakurekisteri.hakija.representation.XMLHakijat
import fi.vm.sade.hakurekisteri.rest.support._
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.web.rest.support.ApiFormat.ApiFormat
import fi.vm.sade.hakurekisteri.web.rest.support.{ApiFormat, IncidentReport, _}
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.{Swagger, SwaggerEngine}
import org.scalatra.util.RicherString._

import scala.compat.Platform
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.util.Try
import scala.xml._

object HakijaQuery {
  def apply(params: Map[String, String], currentUser: Option[User], version: Int): HakijaQuery =
    new HakijaQuery(
      haku = params.get("haku").flatMap(_.blankOption),
      organisaatio = params.get("organisaatio").flatMap(_.blankOption),
      hakukohdekoodi = params.get("hakukohdekoodi").flatMap(_.blankOption),
      hakukohdeOid = params.get("hakukohdeOid").flatMap(_.blankOption),
      hakuehto = Try(Hakuehto.withName(s = params("hakuehto"))).recover { case _ =>
        Hakuehto.Kaikki
      }.get,
      user = currentUser,
      version = version
    )
}

import scala.concurrent.duration._

class HakijaResource(hakijaActor: ActorRef)(implicit
  system: ActorSystem,
  sw: Swagger,
  val security: Security,
  val ct: ClassTag[XMLHakijat]
) extends HakuJaValintarekisteriStack
    with HakijaSwaggerApi
    with HakurekisteriJsonSupport
    with JacksonJsonSupport
    with FutureSupport
    with SecuritySupport
    with ExcelSupport[XMLHakijat]
    with DownloadSupport
    with QueryLogging {

  override protected implicit def executor: ExecutionContext = system.dispatcher
  override protected def applicationDescription: String = "Hakijatietojen rajapinta"
  override protected implicit def swagger: SwaggerEngine[_] = sw
  implicit val defaultTimeout: Timeout = 120.seconds
  override val logger: LoggingAdapter = Logging.getLogger(system, this)

  def getContentType(t: ApiFormat): String = t match {
    case ApiFormat.Json  => formats("json")
    case ApiFormat.Xml   => formats("xml")
    case ApiFormat.Excel => formats("binary")
    case tyyppi          => throw new IllegalArgumentException(s"tyyppi $tyyppi is not supported")
  }

  override protected def renderPipeline: RenderPipeline =
    renderCustom orElse renderExcel orElse super.renderPipeline
  override val streamingRender: (OutputStream, XMLHakijat) => Unit = (out, hakijat) => {

    ExcelUtilV1.write(out, hakijat)
  }
  protected def renderCustom: RenderPipeline = {
    case hakijat: XMLHakijat if format == "xml" =>
      XML.write(
        response.writer,
        Utility.trim(hakijat.toXml),
        response.characterEncoding.get,
        xmlDecl = true,
        doctype = null
      )
  }

  get("/", operation(query)) {
    val t0 = Platform.currentTime
    val q = HakijaQuery(params, currentUser, 1)

    if (q.haku.isEmpty || q.organisaatio.isEmpty) throw HakijaParamMissingException

    val tyyppi = Try(ApiFormat.withName(params("tyyppi"))).getOrElse(ApiFormat.Json)
    contentType = getContentType(tyyppi)

    new AsyncResult() {
      override implicit def timeout: Duration = 120.seconds
      val hakuResult = hakijaActor ? q
      audit.log(auditUser, HakijatLuku, AuditUtil.targetFromParams(params).build(), Changes.EMPTY)
      val hakijatFuture = hakuResult.flatMap {
        case result
            if Try(params("tiedosto").toBoolean).getOrElse(false) || tyyppi == ApiFormat.Excel =>
          setContentDisposition(tyyppi, response, "hakijat")
          Future.successful(result)
        case result =>
          Future.successful(result)
      }

      logQuery(q, t0, hakijatFuture)

      val is = hakijatFuture
    }
  }

  incident {
    case HakijaParamMissingException =>
      (id) =>
        BadRequest(IncidentReport(id, "pakolliset parametrit puuttuvat: haku ja organisaatio"))
    case t: AskTimeoutException =>
      (id) => InternalServerError(IncidentReport(id, "back-end service timed out"))
  }
}
