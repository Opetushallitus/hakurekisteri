package fi.vm.sade.hakurekisteri.web.hakija

import _root_.akka.actor.{ActorRef, ActorSystem}
import _root_.akka.event.{Logging, LoggingAdapter}
import _root_.akka.pattern.{AskTimeoutException, ask}
import _root_.akka.util.Timeout
import fi.vm.sade.auditlog.Changes
import fi.vm.sade.hakurekisteri.hakija._
import fi.vm.sade.hakurekisteri.hakija.representation.JSONHakijatV7
import fi.vm.sade.hakurekisteri.rest.support._
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.web.rest.support._
import fi.vm.sade.hakurekisteri.{AuditUtil, HakijatLuku}
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.{Swagger, SwaggerEngine}

import java.io.OutputStream
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.util.Try

class HakijaResourceV7(hakijaActor: ActorRef)(implicit
  system: ActorSystem,
  sw: Swagger,
  val security: Security,
  val ct: ClassTag[JSONHakijatV7]
) extends HakuJaValintarekisteriStack
    with HakijaSwaggerApiV7
    with HakurekisteriJsonSupport
    with JacksonJsonSupport
    with FutureSupport
    with SecuritySupport
    with ExcelSupport[JSONHakijatV7]
    with DownloadSupport
    with QueryLogging
    with HakijaResourceSupport {
  implicit val defaultTimeout: Timeout = 120.seconds
  override protected implicit def executor: ExecutionContext = system.dispatcher

  override protected def applicationDescription: String = "Hakijatietojen rajapinta"

  override protected implicit def swagger: SwaggerEngine = sw

  override val logger: LoggingAdapter = Logging.getLogger(system, this)

  override protected def renderPipeline: RenderPipeline = renderExcel orElse super.renderPipeline

  override val streamingRender: (OutputStream, JSONHakijatV7) => Unit = (out, hakijat) => {
    ExcelUtilV7.write(out, hakijat)
  }

  get("/", operation(queryV7)) {
    val q = HakijaQuery(params.toMap, currentUser, 7)
    if (q.haku.isEmpty || q.organisaatio.isEmpty) throw HakijaParamMissingException
    val tyyppi = getFormatFromTypeParam()
    val thisResponse = response
    audit.log(auditUser, HakijatLuku, AuditUtil.targetFromParams(params).build(), Changes.EMPTY)
    val hakijatFuture: Future[Any] = (hakijaActor ? q).flatMap {
      case result
          if Try(params("tiedosto").toBoolean).getOrElse(false) || tyyppi == ApiFormat.Excel =>
        setContentDisposition(tyyppi, thisResponse, "hakijat")
        Future.successful(result)
      case result =>
        Future.successful(result)
    }
    prepareAsyncResult(tyyppi, hakijatFuture)
  }

  incident {
    case HakijaParamMissingException =>
      (id) =>
        BadRequest(IncidentReport(id, "pakolliset parametrit puuttuvat: haku ja organisaatio"))
    case t: AskTimeoutException =>
      (id) => InternalServerError(IncidentReport(id, "back-end service timed out"))
  }
}
