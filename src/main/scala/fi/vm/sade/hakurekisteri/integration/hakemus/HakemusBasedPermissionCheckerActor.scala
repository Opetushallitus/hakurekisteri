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

class HakemusBasedPermissionCheckerActor(hakuAppClient: VirkailijaRestClient, organisaatioActor: ActorRef) extends Actor {
  private val acceptedResponseCode: Int = 200
  implicit val ec: ExecutionContext = context.dispatcher
  implicit val defaultTimeout: Timeout = 30.seconds

  def getOrganisationPath(organisaatio: Organisaatio): Seq[String] = {
    organisaatio.children.flatMap(getOrganisationPath) :+ organisaatio.oid
  }

  override def receive: Receive = {
    case HasPermission(user, forPerson) =>
      val allOrgs: Future[Set[String]] = Future.sequence(for (
        organisaatioOid: String <- user.orgsFor("READ", "Virta")
      ) yield (organisaatioActor ? organisaatioOid).mapTo[Option[Organisaatio]].map(_.map(getOrganisationPath))).map(_.flatten.flatten)

      allOrgs.flatMap(orgs => {
        hakuAppClient.postObject[PermissionRequest, PermissionResponse]("haku-app.permissioncheck")(
          acceptedResponseCode,
          PermissionRequest(
            personOidsForSamePerson = Seq(forPerson),
            organisationOids = orgs.toSeq,
            loggedInUserRoles = Seq()
          )
        ).map(_.accessAllowed.getOrElse(false))
      }) pipeTo sender
  }
}
