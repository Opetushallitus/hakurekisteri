package fi.vm.sade.hakurekisteri.batchimport

import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import fi.vm.sade.hakurekisteri.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriJsonSupport, SpringSecuritySupport}
import org.json4s.Extraction
import org.scalatra.servlet.{SizeConstraintExceededException, FileItem, MultipartConfig, FileUploadSupport}
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport

import scala.concurrent.ExecutionContext

class UploadResource()
                    (implicit val system: ActorSystem)
    extends HakuJaValintarekisteriStack with HakurekisteriJsonSupport with JacksonJsonSupport with FutureSupport with CorsSupport with SpringSecuritySupport with FileUploadSupport {

  override protected implicit def executor: ExecutionContext = system.dispatcher
  override val logger: LoggingAdapter = Logging.getLogger(system, this)
  val maxFileSize = 50 * 1024 * 1024L

  configureMultipartHandling(MultipartConfig(maxFileSize = Some(maxFileSize)))

  options("/*") {
    response.setHeader(CorsSupport.AccessControlAllowHeadersHeader, request.getHeader(CorsSupport.AccessControlAllowHeadersHeader))
  }

  before() {
    // Content-Type must be text/html for upload to work properly also in IE
    contentType = formats("html")
  }

  post("/") {
    val fileItem: FileItem = fileParams("tiedosto")
    logger.debug(s"received tiedosto, name ${fileItem.name}, size ${fileItem.size}")

    // TODO validate file

    Ok(UploadResponse("success", "Tiedosto lähetetty", "suoritusrekisteri.tiedonsiirto.tiedostolahetetty").toJson)
  }

  error {
    case t: SizeConstraintExceededException =>
      logger.warning(s"uploaded file is too large: $t")
      RequestEntityTooLarge(UploadResponse("danger", s"Tiedosto on liian suuri (suurin sallittu koko $maxFileSize tavua)", s"suoritusrekisteri.tiedonsiirto.tiedostoliiansuuri_$maxFileSize").toJson)
    case t: Throwable =>
      logger.error(t, "error uploading file")
      InternalServerError(UploadResponse("danger", "Tuntematon virhe lähetettäessä tiedostoa", "suoritusrekisteri.tiedonsiirto.tuntematonvirhe").toJson)
  }

  case class UploadResponse(`type`: String = "success", message: String, messageKey: String, validationErrors: Seq[String] = Seq()) {
    def toJson: String = compact(Extraction.decompose(this))
  }
}

