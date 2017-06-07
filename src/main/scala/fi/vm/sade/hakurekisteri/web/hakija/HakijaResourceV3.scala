package fi.vm.sade.hakurekisteri.web.hakija

import java.io.OutputStream

import _root_.akka.actor.{ActorRef, ActorSystem}
import _root_.akka.event.{Logging, LoggingAdapter}
import _root_.akka.pattern.{AskTimeoutException, ask}
import _root_.akka.util.Timeout
import fi.vm.sade.hakurekisteri.hakija._
import fi.vm.sade.hakurekisteri.hakija.representation.JSONHakijat
import fi.vm.sade.hakurekisteri.rest.support._
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.web.rest.support.{ApiFormat, IncidentReport, _}
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.{Swagger, SwaggerEngine}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.util.Try

class HakijaResourceV3(hakijaActor: ActorRef)
                      (implicit system: ActorSystem, sw: Swagger, val security: Security, val ct: ClassTag[JSONHakijat])
  extends HakuJaValintarekisteriStack with HakijaSwaggerApi with HakurekisteriJsonSupport with JacksonJsonSupport with FutureSupport with SecuritySupport with ExcelSupport[JSONHakijat] with DownloadSupport with QueryLogging with HakijaResourceSupport  {
  implicit val defaultTimeout: Timeout = 120.seconds
  override protected implicit def executor: ExecutionContext = system.dispatcher

  override protected def applicationDescription: String = "Hakijatietojen rajapinta"

  override protected implicit def swagger: SwaggerEngine[_] = sw

  override val logger: LoggingAdapter = Logging.getLogger(system, this)

  override protected def renderPipeline: RenderPipeline = renderExcel orElse super.renderPipeline

  override val streamingRender: (OutputStream, JSONHakijat) => Unit = (out, hakijat) => {
    ExcelUtilV3.write(out, hakijat)
  }

  get("/", operation(queryV2)) {
    if(params.get("haku").getOrElse("").isEmpty)
      throw new IllegalArgumentException(s"Haku can not be empty")
    val q = HakijaQuery(params, currentUser, 3)
    val tyyppi = getFormatFromTypeParam()
    val thisResponse = response
    val hakijatFuture: Future[Any] = (hakijaActor ? q).flatMap {
      case result if Try(params("tiedosto").toBoolean).getOrElse(false) || tyyppi == ApiFormat.Excel =>
        setContentDisposition(tyyppi, thisResponse, "hakijat")
        Future.successful(result)
      case result =>
        Future.successful(result)
    }
    prepareAsyncResult(tyyppi, hakijatFuture)
  }

  incident {
    case t: AskTimeoutException => (id) => InternalServerError(IncidentReport(id, "back-end service timed out"))
  }
}




