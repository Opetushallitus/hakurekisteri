package fi.vm.sade.hakurekisteri.web.jonotus

import java.lang.Boolean._

import _root_.akka.actor.ActorSystem
import _root_.akka.event.{Logging, LoggingAdapter}
import fi.vm.sade.hakurekisteri.integration.valintatulos.InitialLoadingNotDone
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.web.hakija.HakijaResourceSupport
import fi.vm.sade.hakurekisteri.web.rest.support.ApiFormat.ApiFormat
import fi.vm.sade.hakurekisteri.web.rest.support._
import org.scalatra._

class EmptyAsiakirjaException extends RuntimeException()

class AsiakirjaResource(jono: Siirtotiedostojono)(implicit system: ActorSystem, val security: Security)
  extends HakuJaValintarekisteriStack with HakurekisteriJsonSupport with SecuritySupport with DownloadSupport with HakijaResourceSupport {

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
          contentType = getContentType(format)
          setContentDisposition(format, response, "hakijat")
          response.outputStream.write(bytes)
        }
      case AsiakirjaWithExceptions(exception) =>
        if(isStatusCheck) {
          exception match {
            case i:InitialLoadingNotDone =>
              NoContent(reason = "suoritusrekisteri.poikkeus.alustuskesken")
            case e:EmptyAsiakirjaException =>
              NoContent(reason = "suoritusrekisteri.poikkeus.eisisaltoa")
            case _ =>
              NoContent(reason = "suoritusrekisteri.poikkeus.tuntematon")
          }
        } else {
          NotFound(exception.toString)
        }
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
