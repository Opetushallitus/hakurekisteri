package fi.vm.sade.hakurekisteri.web.kkhakija

import java.io.OutputStream

import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import fi.vm.sade.auditlog.Changes
import fi.vm.sade.hakurekisteri.integration.haku.HakuNotFoundException
import fi.vm.sade.hakurekisteri.integration.tarjonta.TarjontaException
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.web.hakija.HakijaResourceSupport
import fi.vm.sade.hakurekisteri.web.rest.support.{ApiFormat, IncidentReport, _}
import fi.vm.sade.hakurekisteri.{AuditUtil, Config, KKHakijatLuku}
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.{Swagger, SwaggerEngine}

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.util.Try

class KkHakijaResourceV2(kkHakijaService: KkHakijaService, ophConfig: Config)(implicit
  system: ActorSystem,
  sw: Swagger,
  val security: Security,
  val ct: ClassTag[Seq[Hakija]]
) extends HakuJaValintarekisteriStack
    with KkHakijaSwaggerApi
    with HakurekisteriJsonSupport
    with JacksonJsonSupport
    with FutureSupport
    with SecuritySupport
    with ExcelSupport[Seq[Hakija]]
    with DownloadSupport
    with QueryLogging
    with HakijaResourceSupport {

  protected def applicationDescription: String = "Korkeakouluhakijatietojen rajapinta"
  protected implicit def swagger: SwaggerEngine = sw
  override protected implicit def executor: ExecutionContext = system.dispatcher
  override val logger: LoggingAdapter = Logging.getLogger(system, this)
  override protected def renderPipeline: RenderPipeline = renderExcel orElse super.renderPipeline
  override val streamingRender: (OutputStream, Seq[Hakija]) => Unit = KkExcelUtilV2.write

  get("/", operation(query)) {
    val q = KkHakijaQuery(params.toMap, currentUser)
    val tyyppi = getFormatFromTypeParam()
    if (q.oppijanumero.isEmpty && q.hakukohde.isEmpty) throw KkHakijaParamMissingException
    val thisResponse = response
    audit.log(auditUser, KKHakijatLuku, AuditUtil.targetFromParams(params).build(), Changes.EMPTY)
    val kkhakijatFuture = kkHakijaService.getKkHakijat(q, 2).flatMap {
      case result
          if Try(params("tiedosto").toBoolean).getOrElse(false) || tyyppi == ApiFormat.Excel =>
        setContentDisposition(tyyppi, thisResponse, "hakijat")
        Future.successful(result)
      case result => Future.successful(result)
    }
    prepareAsyncResult(tyyppi, kkhakijatFuture, requestTimeout = ophConfig.valintaTulosTimeout)
  }

  incident {
    case KkHakijaParamMissingException =>
      (id) =>
        BadRequest(IncidentReport(id, "either parameter oppijanumero or hakukohde must be given"))
    case t: TarjontaException =>
      (id) => InternalServerError(IncidentReport(id, s"error with tarjonta: $t"))
    case t: HakuNotFoundException => (id) => NotFound(IncidentReport(id, s"$t"))
    case t: InvalidSyntymaaikaException =>
      (id) => InternalServerError(IncidentReport(id, s"error: $t"))
    case t: InvalidKausiException => (id) => InternalServerError(IncidentReport(id, s"error: $t"))
  }

}
