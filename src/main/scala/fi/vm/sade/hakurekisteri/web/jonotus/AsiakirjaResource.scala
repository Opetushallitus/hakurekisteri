package fi.vm.sade.hakurekisteri.web.jonotus

import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.web.hakija.HakijaResourceSupport
import fi.vm.sade.hakurekisteri.web.rest.support._

class AsiakirjaResource(jono: Siirtotiedostojono)(implicit system: ActorSystem, val security: Security)
  extends HakuJaValintarekisteriStack with HakurekisteriJsonSupport with SecuritySupport with DownloadSupport with HakijaResourceSupport {

  override val logger: LoggingAdapter = Logging.getLogger(system, this)

  get("/:id") {
    (currentUser, params.get("id")) match {
      case (Some(user), Some(id)) =>
        logger.info(s"User $user downloaded file $id")
        jono.getAsiakirjaWithId(id) match {
          case Some((format, bytes)) =>
            contentType = getContentType(format)
            setContentDisposition(format, response, "hakijat")
            response.outputStream.write(bytes)
          case _ =>
            halt(status = 404, body=s"$id not found!")
        }
      case _ =>
        halt(status = 401, body=s"User not authorized!")
    }
  }
}
