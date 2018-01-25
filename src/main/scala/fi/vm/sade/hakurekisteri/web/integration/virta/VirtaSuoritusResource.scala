package fi.vm.sade.hakurekisteri.web.integration.virta

import akka.actor.{ActorRef, ActorSystem}
import akka.event.{Logging, LoggingAdapter}
import akka.pattern.{AskTimeoutException, ask}
import akka.util.Timeout
import fi.vm.sade.auditlog.hakurekisteri.{HakuRekisteriOperation, LogMessage}
import fi.vm.sade.hakurekisteri.integration.hakemus.HasPermission
import fi.vm.sade.hakurekisteri.integration.henkilo.{Henkilo, IOppijaNumeroRekisteri}
import fi.vm.sade.hakurekisteri.integration.virta.{VirtaQuery, VirtaResult}
import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriJsonSupport, User}
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.web.rest.support.{IncidentReport, Security, SecuritySupport, UserNotAuthorized}
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.{Swagger, SwaggerEngine}
import org.scalatra.{AsyncResult, FutureSupport, InternalServerError}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.matching.Regex

class VirtaSuoritusResource(virtaActor: ActorRef, hakemusBasedPermissionChecker: ActorRef, oppijaNumeroRekisteri: IOppijaNumeroRekisteri)
                           (implicit val system: ActorSystem, sw: Swagger, val security: Security)
  extends HakuJaValintarekisteriStack with HakurekisteriJsonSupport with VirtaSuoritusSwaggerApi with JacksonJsonSupport
    with SecuritySupport with FutureSupport {
  override val logger: LoggingAdapter = Logging.getLogger(system, this)
  override protected implicit def swagger: SwaggerEngine[_] = sw
  override protected implicit def executor: ExecutionContext = system.dispatcher
  override protected def applicationDescription: String = "Henkilön suoritusten haun rajapinta Virta-palvelusta"
  implicit val defaultTimeout: Timeout = 30.seconds

  def hasAccess(personOid: String, user: User): Future[Boolean] =
    if (user.isAdmin) {
      Future.successful(true)
    } else {
      (hakemusBasedPermissionChecker ? HasPermission(user, personOid)).mapTo[Boolean]
    }

  before() {
    contentType = formats("json")
  }

  private def auditlogQuery(username: String, henkilo: String): Unit = {
    audit.log(LogMessage.builder()
      .id(username)
      .setOperaatio(HakuRekisteriOperation.READ_VIRTA_TIEDOT)
      .setResourceId(henkilo)
      .build()
    )
  }

  get("/:hetu", operation(query)) {
    val hetu = params("hetu")
    val user = currentUser.getOrElse(throw new UserNotAuthorized("not authorized"))
    new AsyncResult() {
      override implicit def timeout: Duration = 30.seconds
      override val is =
        oppijaNumeroRekisteri.getByHetu(hetu).flatMap(henkilo => {
          hasAccess(henkilo.oidHenkilo, user).flatMap(access => {
            if (access) {
              auditlogQuery(user.username, henkilo.oidHenkilo)
              virtaActor ? VirtaQuery(oppijanumero = henkilo.oidHenkilo, hetu = Some(hetu))
            } else {
              Future.successful(VirtaResult(hetu))
            }
          })
        })
    }
  }

  incident {
    case t: AskTimeoutException => (id) => InternalServerError(IncidentReport(id, "back-end service timed out"))
    case e: Throwable => (id) => InternalServerError(IncidentReport(id, "unexpected error"))
  }


}
