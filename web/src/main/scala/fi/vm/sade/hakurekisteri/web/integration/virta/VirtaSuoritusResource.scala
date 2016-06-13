package fi.vm.sade.hakurekisteri.web.integration.virta

import akka.actor.{ActorRef, ActorSystem}
import akka.event.{Logging, LoggingAdapter}
import akka.pattern.{AskTimeoutException, ask}
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.integration.hakemus.HasPermission
import fi.vm.sade.hakurekisteri.integration.henkilo.{Henkilo, HetuQuery}
import fi.vm.sade.hakurekisteri.integration.virta.{VirtaQuery, VirtaResult}
import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriJsonSupport, User}
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.web.rest.support.{IncidentReport, Security, SecuritySupport}
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.{Swagger, SwaggerEngine}
import org.scalatra.{AsyncResult, FutureSupport, InternalServerError}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.matching.Regex

class VirtaSuoritusResource(virtaActor: ActorRef, hakuAppPermissionChecker: ActorRef, henkiloActor: ActorRef)
                           (implicit val system: ActorSystem, sw: Swagger, val security: Security)
  extends HakuJaValintarekisteriStack with HakurekisteriJsonSupport with VirtaSuoritusSwaggerApi with JacksonJsonSupport
    with SecuritySupport with FutureSupport {
  override val logger: LoggingAdapter = Logging.getLogger(system, this)
  override protected implicit def swagger: SwaggerEngine[_] = sw
  override protected implicit def executor: ExecutionContext = system.dispatcher
  override protected def applicationDescription: String = "Henkilön suoritusten haun rajapinta Virta-palvelusta"
  val hetuPattern: Regex = "[0-9]{6}[A+-][0-9]{3}[0-9A-FHJ-NPR-Y]$".r
  implicit val defaultTimeout: Timeout = 30.seconds

  // XXX: Replace with proper permission checks
  def hasAccess(personOid: String, user: Option[User]): Future[Boolean] =
    if (user.exists(_.isAdmin)) {
      Future.successful(true)
    } else {
      (hakuAppPermissionChecker ? HasPermission(user.get, personOid)).mapTo[Boolean]
    }

  before() {
    contentType = formats("json")
  }

  def parsePIN(id: String): Option[String] = {
    hetuPattern.findFirstIn(id)
  }

  get("/:hetu", operation(query)) {
    val hetu = params("hetu")
    val user = currentUser
    new AsyncResult() {
      override implicit def timeout: Duration = 30.seconds
      override val is =
        (henkiloActor ? HetuQuery(hetu)).mapTo[Henkilo].flatMap(henkilo => {
          hasAccess(henkilo.oidHenkilo, user).flatMap(access => {
            if (access) {
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