package fi.vm.sade.hakurekisteri.web.integration.virta

import akka.actor.{ActorSystem}
import akka.event.{Logging, LoggingAdapter}
import akka.pattern.{AskTimeoutException, ask}
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.integration.hakemus.{
  HakemusBasedPermissionCheckerActorRef,
  HasPermission
}
import fi.vm.sade.auditlog.{Changes, Target}
import fi.vm.sade.hakurekisteri.HenkilonTiedotVirrasta
import fi.vm.sade.hakurekisteri.integration.henkilo.{Henkilo, HetuUtil, IOppijaNumeroRekisteri}
import fi.vm.sade.hakurekisteri.integration.virta.{VirtaQuery, VirtaResourceActorRef, VirtaResult}
import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriJsonSupport, User}
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.web.rest.support.{
  IncidentReport,
  Security,
  SecuritySupport,
  UserNotAuthorized
}
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.{Swagger, SwaggerEngine}
import org.scalatra.{AsyncResult, FutureSupport, InternalServerError}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

class VirtaSuoritusResource(
  virtaActor: VirtaResourceActorRef,
  hakemusBasedPermissionChecker: HakemusBasedPermissionCheckerActorRef,
  oppijaNumeroRekisteri: IOppijaNumeroRekisteri
)(implicit val system: ActorSystem, sw: Swagger, val security: Security)
    extends HakuJaValintarekisteriStack
    with HakurekisteriJsonSupport
    with VirtaSuoritusSwaggerApi
    with JacksonJsonSupport
    with SecuritySupport
    with FutureSupport {
  override val logger: LoggingAdapter = Logging.getLogger(system, this)
  override protected implicit def swagger: SwaggerEngine[_] = sw
  override protected implicit def executor: ExecutionContext = system.dispatcher
  override protected def applicationDescription: String =
    "Henkilön suoritusten haun rajapinta Virta-palvelusta"
  implicit val defaultTimeout: Timeout = 30.seconds

  def hasAccess(personOid: String, user: User): Future[Boolean] =
    if (user.isAdmin) {
      Future.successful(true)
    } else {
      (hakemusBasedPermissionChecker.actor ? HasPermission(user, personOid)).mapTo[Boolean]
    }

  def queryForSuoritustiedotFromVirta(
    henkilo: Henkilo,
    hetuOrHenkiloOid: String,
    user: User,
    au: fi.vm.sade.auditlog.User
  ): Future[Any] = {
    hasAccess(henkilo.oidHenkilo, user).flatMap(access => {
      if (access) {
        audit.log(
          au,
          HenkilonTiedotVirrasta,
          new Target.Builder().setField("hetu", henkilo.hetu.getOrElse(hetuOrHenkiloOid)).build(),
          new Changes.Builder().build()
        )
        virtaActor.actor ? VirtaQuery(oppijanumero = henkilo.oidHenkilo, hetu = henkilo.hetu)
      } else {
        Future.successful(VirtaResult(hetuOrHenkiloOid))
      }
    })
  }

  before() {
    contentType = formats("json")
  }

  get("/:hetu", operation(query)) {
    val hetuOrHenkiloOid = params("hetu")
    val user = currentUser.getOrElse(throw new UserNotAuthorized("not authorized"))
    val au = security.auditUser
    new AsyncResult() {
      override implicit def timeout: Duration = 30.seconds
      override val is =
        if (HetuUtil.toSyntymaAika(hetuOrHenkiloOid).isDefined) {
          oppijaNumeroRekisteri
            .getByHetu(hetuOrHenkiloOid)
            .flatMap(henkilo => {
              queryForSuoritustiedotFromVirta(henkilo, hetuOrHenkiloOid, user, au)
            })
        } else {
          // getByOids queries for master henkilos with the given oid,
          // therefore the returned head should be the master henkilo of this henkiloOid
          oppijaNumeroRekisteri
            .getByOids(Set(hetuOrHenkiloOid))
            .flatMap(map => {
              queryForSuoritustiedotFromVirta(map.head._2, hetuOrHenkiloOid, user, au)
            })
        }
    }
  }

  incident {
    case t: AskTimeoutException =>
      (id) => InternalServerError(IncidentReport(id, "back-end service timed out"))
    case e: Throwable => (id) => InternalServerError(IncidentReport(id, "unexpected error"))
  }

}
