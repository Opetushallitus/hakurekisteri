package fi.vm.sade.hakurekisteri.integration.hakemus

import akka.actor.{Actor, ActorRef}
import akka.pattern.ask
import akka.pattern.pipe
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient
import fi.vm.sade.hakurekisteri.integration.organisaatio.Organisaatio
import fi.vm.sade.hakurekisteri.rest.support.User

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.language.implicitConversions

case class HasPermission(user: User, hetu: String)
case class PermissionRequest(personOidsForSamePerson: Seq[String], organisationOids: Seq[String], loggedInUserRoles: Seq[String])
case class PermissionResponse(accessAllowed: Option[Boolean] = None, errorMessage: Option[String] = None)

class HakemusBasedPermissionCheckerActor(hakuAppClient: VirkailijaRestClient,
                                         ataruClient: VirkailijaRestClient,
                                         organisaatioActor: ActorRef) extends Actor {
  private val acceptedResponseCode: Int = 200
  implicit val ec: ExecutionContext = context.dispatcher
  implicit val defaultTimeout: Timeout = 30.seconds

  private def getOrganisationPath(organisaatio: Organisaatio): Set[String] = {
    def go(organisations: List[Organisaatio], oids: Set[String]): Set[String] = organisations match {
      case Nil => oids
      case org :: rest => go(rest ++ org.children, oids + org.oid)
    }
    go(List(organisaatio), Set())
  }

  private def checkHakuApp(forPerson: String, orgs: Set[String]): Future[Boolean] = {
    hakuAppClient.postObject[PermissionRequest, PermissionResponse]("haku-app.permissioncheck")(
      acceptedResponseCode,
      PermissionRequest(
        personOidsForSamePerson = Seq(forPerson),
        organisationOids = orgs.toSeq,
        loggedInUserRoles = Seq()
      )
    ).map(_.accessAllowed.getOrElse(false))
  }

  private def checkAtaru(forPerson: String, orgs: Set[String]): Future[Boolean] = {
    ataruClient.postObject[PermissionRequest, PermissionResponse]("ataru.permissioncheck")(
      acceptedResponseCode,
      PermissionRequest(
        personOidsForSamePerson = Seq(forPerson),
        organisationOids = orgs.toSeq,
        loggedInUserRoles = Seq()
      )
    ).map(_.accessAllowed.getOrElse(false))
  }

  override def receive: Receive = {
    case HasPermission(user, forPerson) =>
      Future.sequence(user.orgsFor("READ", "Virta").map(oid => (organisaatioActor ? oid).mapTo[Option[Organisaatio]]))
        .map(_.collect { case Some(org) => org }.flatMap(getOrganisationPath))
        .flatMap(orgs => {
          checkHakuApp(forPerson, orgs).zip(checkAtaru(forPerson, orgs)).map {
            case (false, false) => false
            case _ => true
          }
        }) pipeTo sender
  }
}