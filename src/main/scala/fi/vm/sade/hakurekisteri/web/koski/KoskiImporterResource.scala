package fi.vm.sade.hakurekisteri.web.koski

import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import fi.vm.sade.auditlog.hakurekisteri.{HakuRekisteriOperation, LogMessage}
import fi.vm.sade.hakurekisteri.integration.koski.{IKoskiService, KoskiService}
import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriJsonSupport, User}
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.web.rest.support.{Security, SecuritySupport, UserNotAuthorized}
import org.scalatra.{AsyncResult, FutureSupport}
import org.scalatra.swagger.{Swagger, SwaggerEngine}

import scala.compat.Platform
import scala.concurrent.{ExecutionContext, Future}



class KoskiImporterResource(koskiService: IKoskiService)
                           (implicit val system: ActorSystem, sw: Swagger, val security: Security)
  extends HakuJaValintarekisteriStack
    with KoskiImporterSwaggerApi
    with HakurekisteriJsonSupport
    with FutureSupport
    with SecuritySupport  {

  override protected implicit def executor: ExecutionContext = system.dispatcher

  override val logger: LoggingAdapter = Logging.getLogger(system, this)

  override protected implicit def swagger: SwaggerEngine[_] = sw

  override protected def applicationDescription: String = "Koski integraation rest-api"


  def getAdmin: User = {
    currentUser match {
      case Some(u) if u.isAdmin => u
      case None => throw UserNotAuthorized(s"anonymous access not allowed")
    }
  }

  get("/:oppijaOid", operation(read)) {
    implicit val user: User = getAdmin
    val personOid = params("oppijaOid")
    audit.log(LogMessage.builder()
      .id(user.username)
      .setOperaatio(HakuRekisteriOperation.RESOURCE_UPDATE)
      .setResourceId(personOid)
      .build())
    new AsyncResult {
      override val is: Future[_] = koskiService.updateHenkilo(personOid)
    }
  }

}