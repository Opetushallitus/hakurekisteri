package fi.vm.sade.hakurekisteri.integration.hakemus

import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.organisaatio.{Organisaatio, OrganisaatioActorRef}
import fi.vm.sade.hakurekisteri.integration.{ExecutorUtil, VirkailijaRestClient}
import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriJsonSupport, User}
import fi.vm.sade.scala.future.OphFutures
import org.json4s.jackson.Serialization
import support.TypedActorRef

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions

case class HasPermission(user: User, hetu: String)
case class HasPermissionFromOrgs(orgs: Set[String], hetu: String)
case class PermissionRequest(personOidsForSamePerson: Seq[String], organisationOids: Seq[String], loggedInUserRoles: Seq[String])
case class PermissionResponse(accessAllowed: Option[Boolean] = None, errorMessage: Option[String] = None)

class HakemusBasedPermissionCheckerActor(hakuAppClient: VirkailijaRestClient,
                                         ataruClient: VirkailijaRestClient,
                                         organisaatioActor: OrganisaatioActorRef,
                                         config: Config) extends Actor with ActorLogging {
  private val acceptedResponseCode: Int = 200
  implicit val ec: ExecutionContext = ExecutorUtil.createExecutor(config.integrations.asyncOperationThreadPoolSize, getClass.getSimpleName)
  implicit val defaultTimeout: Timeout = 30.seconds

  private def getOrganisationPath(organisaatio: Organisaatio): Set[String] = {
    def go(organisations: List[Organisaatio], oids: Set[String]): Set[String] = organisations match {
      case Nil => oids
      case org :: rest => go(rest ++ org.children, oids + org.oid)
    }
    go(List(organisaatio), Set())
  }

  private def checkHakuApp(forPerson: String, orgs: Set[String]): Future[Boolean] = {
    val permissionRequest = PermissionRequest(
      personOidsForSamePerson = Seq(forPerson),
      organisationOids = orgs.toSeq,
      loggedInUserRoles = Seq()
    )
    log.debug(s"Querying permissions from haku-app with ${Serialization.write(permissionRequest)(HakurekisteriJsonSupport.format)}")
    hakuAppClient.postObject[PermissionRequest, PermissionResponse]("haku-app.permissioncheck")(
      acceptedResponseCode,
      permissionRequest
    ).map { permissionResponse =>
      log.debug(s"Got permission response for data of $forPerson from haku-app: $permissionResponse")
      permissionResponse.accessAllowed.getOrElse(false)
    }
  }

  private def checkAtaru(forPerson: String, orgs: Set[String]): Future[Boolean] = {
    val permissionRequest = PermissionRequest(
      personOidsForSamePerson = Seq(forPerson),
      organisationOids = orgs.toSeq,
      loggedInUserRoles = Seq()
    )
    log.debug(s"Querying permissions from ataru with ${Serialization.write(permissionRequest)(HakurekisteriJsonSupport.format)}")
    ataruClient.postObject[PermissionRequest, PermissionResponse]("ataru.permissioncheck")(
      acceptedResponseCode,
      permissionRequest
    ).map { permissionResponse =>
      log.debug(s"Got permission response for data of $forPerson from ataru: $permissionResponse")
      permissionResponse.accessAllowed.getOrElse(false)
    }
  }

  private def hasPermissionFor(forPerson: String, orgs: Set[String]): Future[Boolean] = {
    log.debug(s"hasPermissionFor method called with arguments forPerson == $forPerson , orgs == $orgs")
    Future.sequence(orgs.map(oid => (organisaatioActor.actor ? oid).mapTo[Option[Organisaatio]]))
      .map(_.collect { case Some(org) => org }.flatMap(getOrganisationPath))
      .flatMap(orgs =>
        OphFutures.parallelOr(checkHakuApp(forPerson, orgs), checkAtaru(forPerson, orgs)))
  }

  override def receive: Receive = {
    case message@HasPermission(user, forPerson) =>
      log.debug(s"received ${message.copy(hetu = "<censored>")}")
      val orgs: Set[String] = user.orgsFor("READ", "Virta")
      hasPermissionFor(forPerson, orgs) pipeTo sender

    case message@HasPermissionFromOrgs(orgs, forPerson) =>
      log.debug(s"received ${message.copy(hetu = "<censored>")}")
      hasPermissionFor(forPerson, orgs) pipeTo sender
  }
}

case class HakemusBasedPermissionCheckerActorRef(actor: ActorRef) extends TypedActorRef
