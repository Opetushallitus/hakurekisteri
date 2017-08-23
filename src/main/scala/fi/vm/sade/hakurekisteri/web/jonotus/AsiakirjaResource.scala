package fi.vm.sade.hakurekisteri.web.jonotus

import java.io.OutputStream
import java.lang.Boolean._
import java.util.concurrent.{TimeoutException, ExecutionException}

import _root_.akka.actor.ActorSystem
import _root_.akka.event.{Logging, LoggingAdapter}
import fi.vm.sade.hakurekisteri.integration.PreconditionFailedException
import fi.vm.sade.hakurekisteri.integration.koodisto.Koodisto
import fi.vm.sade.hakurekisteri.integration.valintatulos.InitialLoadingNotDone
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.web.hakija.HakijaResourceSupport
import fi.vm.sade.hakurekisteri.web.kkhakija.Hakija
import fi.vm.sade.hakurekisteri.web.rest.support.ApiFormat.ApiFormat
import fi.vm.sade.hakurekisteri.web.rest.support._
import org.json4s.Formats
import org.scalatra._
import org.json4s._
import org.json4s.jackson.Serialization.write

class EmptyAsiakirjaException extends RuntimeException()
case class LocalizedMessage(message: String, parameter: Option[String] = None)
class AsiakirjaResource(jono: Siirtotiedostojono)(implicit system: ActorSystem, val security: Security)
  extends HakuJaValintarekisteriStack with HakurekisteriJsonSupport with SecuritySupport with DownloadSupport with HakijaResourceSupport {

  override protected implicit def jsonFormats: Formats = HakurekisteriJsonSupport.format

  addMimeMapping("application/octet-stream", "binary")

  override val logger: LoggingAdapter = Logging.getLogger(system, this)

  get("/:id") {
    val isStatusCheck = params.get("status").exists(parseBoolean)
    toEvent() match {
      case NotAuthorized() =>
        halt(status = 401, body=s"User is not document owner or authorized!")
      case AsiakirjaNotFound() =>
        halt(status = 404, body=s"Resource not found!")
      case Asiakirja(format, bytes) =>
        if(isStatusCheck) {
          Ok()
        } else {
          getContentType(format) match {
            case Left(ctype) =>
              contentType = ctype
              setContentDisposition(format, response, "hakijat")
              response.outputStream.write(bytes)

            case Right(ex) =>
              logger.error("Unsupported content type", ex)
              throw ex
          }

        }
      case AsiakirjaWithExceptions(exception) =>
        if(isStatusCheck) {
          exceptionToNoContentResponse(exception)
        } else {
          NotFound(exception.toString)
        }
    }
  }
  def exceptionToNoContentResponse(exception: Exception): ActionResult = {
    exception match {
      case t: TimeoutException =>
        InternalServerError(body = write(LocalizedMessage("suoritusrekisteri.poikkeus.aikakatkaisu")))
      case e: ExecutionException =>
        e.getCause match {
          case p: PreconditionFailedException =>
            val KoodistoUrl = ".*koodisto-service/rest/json/relaatio/rinnasteinen/([^,]*).*".r
            p.message match {
              case KoodistoUrl(koodi) =>
                InternalServerError(body = write(LocalizedMessage("suoritusrekisteri.poikkeus.koodisto", Some(koodi))))
              case _ =>
                InternalServerError(body = write(LocalizedMessage("suoritusrekisteri.poikkeus.taustapalveluvirhe", Some(p.message))))
            }
          case _ =>
            InternalServerError(body = write(LocalizedMessage("suoritusrekisteri.poikkeus.tuntematon")))
        }
      case i: InitialLoadingNotDone =>
        InternalServerError(body = write(LocalizedMessage("suoritusrekisteri.poikkeus.alustuskesken")))
      case e: EmptyAsiakirjaException =>
        NoContent()
      case _ =>
        InternalServerError(body = write(LocalizedMessage("suoritusrekisteri.poikkeus.tuntematon")))
    }
  }

  private def toEvent(): Event = {
    currentUser match {
      case Some(user) =>
        params.get("id") match {
          case Some(id) =>
            jono.getAsiakirjaWithId(id) match {
              case Some((format, status, Some(owner))) =>
                if(owner.username.equals(user.username)) {

                  status match {
                    case Left(exception) =>
                      logger.error("Create asiakirja with exception: {}", exception)
                      AsiakirjaWithExceptions(exception)
                    case Right(bytes) =>
                      Asiakirja(format, bytes)
                  }
                } else {
                  NotAuthorized()
                }
              case _ =>
                AsiakirjaNotFound()
            }
          case _ =>
            AsiakirjaNotFound()
        }
      case _ =>
        NotAuthorized()
    }
  }

}
trait Event
case class NotAuthorized() extends Event
case class AsiakirjaNotFound() extends Event
case class Asiakirja(format: ApiFormat, data: Array[Byte]) extends Event
case class AsiakirjaWithExceptions(exception: Exception) extends Event
