package fi.vm.sade.hakurekisteri.web.jonotus

import java.io.OutputStream

import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import fi.vm.sade.hakurekisteri.hakija.{ExcelUtilV2, JSONHakijat}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.web.hakija.{HakijaResourceSupport, HakijaSwaggerApi}
import fi.vm.sade.hakurekisteri.web.rest.support._
import org.scalatra.FutureSupport
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.Swagger

import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag

class AsiakirjaResource(jono: Siirtotiedostojono)(implicit system: ActorSystem, sw: Swagger, val security: Security, val ct: ClassTag[JSONHakijat])
  extends HakuJaValintarekisteriStack with HakurekisteriJsonSupport with JacksonJsonSupport with FutureSupport with SecuritySupport with ExcelSupport[JSONHakijat] with DownloadSupport with QueryLogging with HakijaResourceSupport  {

  override val logger: LoggingAdapter = Logging.getLogger(system, this)

  override protected implicit def executor: ExecutionContext = system.dispatcher

  override val streamingRender: (OutputStream, JSONHakijat) => Unit = (out, hakijat) => {
    ExcelUtilV2.write(out, hakijat)
  }

  get("/asiakirja/:id") {
    (currentUser, params.get("id")) match {
      case (Some(user), Some(id)) =>
        logger.info(s"User $user downloaded file $id")
      case _ =>
        logger.error(s"No such file or user $currentUser not authorized!")
    }
  }
}
