package fi.vm.sade.hakurekisteri.organization

import java.util.UUID

import akka.actor.{Actor, ActorRef, Cancellable, Status}
import akka.event.Logging
import dispatch.Defaults._
import dispatch._
import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient
import fi.vm.sade.hakurekisteri.rest.support.{Query, Resource, User}
import fi.vm.sade.hakurekisteri.storage.{DeleteResource, Identified}
import fi.vm.sade.hakurekisteri.{Config, Oids}
import org.joda.time.DateTime

import scala.concurrent.duration._

class OrganizationHierarchy[A <: Resource[I, A] :Manifest, I: Manifest](filteredActor: ActorRef, organizationFinder: Seq[A] => Seq[AuthorizationSubject[A]], config: Config, organisaatioClient: VirkailijaRestClient)
  extends FutureOrganizationHierarchy[A, I](filteredActor, new AuthorizationSubjectFinder[A] {
    override def apply(v1: Seq[A]): Future[Seq[AuthorizationSubject[A]]] = Future.successful(organizationFinder(v1))
  }, config, organisaatioClient = organisaatioClient)

class FutureOrganizationHierarchy[A <: Resource[I, A] :Manifest, I: Manifest]
(filteredActor: ActorRef,
 authorizationSubjectFinder: AuthorizationSubjectFinder[A],
 config: Config, organisaatioClient: VirkailijaRestClient) extends Actor {
  val logger = Logging(context.system, this)
  implicit val timeout: akka.util.Timeout = 450.seconds
  private var authorizer: OrganizationAuthorizer = OrganizationAuthorizer(Map())
  private var organizationCacheUpdater: Cancellable = _

  case object Update

  override def preStart() {
    organizationCacheUpdater = context.system.scheduler.schedule(
      0.seconds, config.integrations.organisaatioCacheHours.hours,
      self, Update)
  }

  override def postStop() {
    organizationCacheUpdater.cancel()
  }

  import akka.pattern.{ask, pipe}
  override def receive: Receive = {
    case Update =>
      organisaatioClient.readObject[OrganisaatioHakutulos]("organisaatio-service.hierarkia.hae.aktiiviset")(200, config.integrations.organisaatioConfig.httpClientMaxRetries)
        .map(FutureOrganizationHierarchy.parseOrganizationHierarchy) pipeTo self

    case a: OrganizationAuthorizer =>
      logger.info("organization paths loaded")
      authorizer = a

    case Status.Failure(t) =>
      logger.error(t, "failed to load organization paths")

    case AuthorizedQuery(q, user) =>
      (filteredActor ? q).mapTo[Seq[A with Identified[UUID]]].flatMap(authorizedResources(_, user, "READ")) pipeTo sender

    case AuthorizedReadWithOrgsChecked(id, user) =>
      (filteredActor ? id).mapTo[Option[A with Identified[UUID]]] pipeTo sender

    case AuthorizedRead(id, user) =>
      (filteredActor ? id).mapTo[Option[A with Identified[UUID]]].flatMap(checkRights(user, "READ")) pipeTo sender

    case AuthorizedDelete(id, user)  =>
      val checkedRights = for (resourceToDelete <- filteredActor ? id;
                               rights <- checkRights(user, "DELETE")(resourceToDelete.asInstanceOf[Option[A]]);
                               result: Boolean <- if (rights.isDefined) (filteredActor ? DeleteResource(id, user.username)).map(r => true) else Future.successful(false)
      ) yield result
      checkedRights pipeTo sender

    case AuthorizedCreate(resource:A, user) =>
      filteredActor forward resource

    case AuthorizedUpdate(resource: A, user) =>
      val checked = for (resourceToUpdate <- filteredActor ? resource.identify.id;
                         rightsForOld <- checkRights(user, "WRITE")(resourceToUpdate.asInstanceOf[Option[A]]);
                         rightsForNew <- checkRights(user, "WRITE")(Some(resource));
                         result <- if (rightsForOld.isDefined && rightsForNew.isDefined) filteredActor ? resource else Future.successful(rightsForOld)
      ) yield result
      checked pipeTo sender

    case message: AnyRef =>
      filteredActor forward message
  }

  def checkRights(user: User, action:String)(item:Option[A]) = item match {
    case None => Future.successful(None)
    case Some(resource) => isAuthorized(user, action, resource).map(authorized => if (authorized) Some(resource) else None)
  }

  private def subjectFinder(resources: Seq[A])(implicit m: Manifest[A]): Future[Seq[(A, Subject)]] =
    authorizationSubjectFinder(resources).map(_.map(o => (o.item, Subject(m.runtimeClass.getSimpleName, o.orgs, o.komo))))

  private def isAuthorized(user:User, action: String, item: A): concurrent.Future[Boolean] =
    subjectFinder(Seq(item)).map {
      case (_, subject) :: _ => authorizer.checkAccess(user, action, subject)
    }

  private def authorizedResources(resources: Seq[A], user: User, action: String): Future[Seq[A]] = {
    subjectFinder(resources).map(_.collect {
      case (item, subject) if authorizer.checkAccess(user, action, subject) => item
    })
  }
}

object FutureOrganizationHierarchy {
  private def parentOids(org: OrganisaatioPerustieto): (String, Set[String]) =
    (org.oid, org.parentOidPath.split("/").toSet)

  private def flattenAndInverseHierarchy(org: OrganisaatioPerustieto): Map[String, Set[String]] =
    org.children.map(flattenAndInverseHierarchy).fold(Map())(_ ++ _) + parentOids(org)

  def parseOrganizationHierarchy(hakutulos: OrganisaatioHakutulos): OrganizationAuthorizer = {
    OrganizationAuthorizer(hakutulos.organisaatiot.map(flattenAndInverseHierarchy).fold(Map())(_ ++ _))
  }
}

case class AuthorizedQuery[A](q: Query[A],  user: User)
case class AuthorizedRead[I](id: I, user: User)
case class AuthorizedReadWithOrgsChecked[I](id: I, user: User)

case class AuthorizedDelete[I](id: I, user: User)
case class AuthorizedCreate[A <: Resource[I, A], I](q: A,  user: User)
case class AuthorizedUpdate[A <: Resource[I, A] :Manifest, I : Manifest](q: A with Identified[I], user: User)

case class Subject(resource: String, orgs: Set[String], komo: Option[String])
case class OrganisaatioPerustieto(oid: String, alkuPvm: String, lakkautusPvm: Option[Long], parentOid: String, parentOidPath: String,
                                  ytunnus: Option[String], oppilaitosKoodi: Option[String], oppilaitostyyppi: Option[String], toimipistekoodi: Option[String],
                                  `match`: Boolean, kieletUris: List[String], kotipaikkaUri: String,
                                  children: List[OrganisaatioPerustieto], aliOrganisaatioMaara: Integer,
                                  virastoTunnus: Option[String], organisaatiotyypit: List[String])
case class OrganisaatioHakutulos(numHits: Integer, organisaatiot: List[OrganisaatioPerustieto])

case class OrganizationAuthorizer(ancestors: Map[String, Set[String]]) {
  def checkAccess(user: User, action: String, target: Subject): Boolean = {
    val allowedOrgs = user.orgsFor(action, target.resource)
    val targetAncestors = target.orgs.flatMap(oid => ancestors.getOrElse(oid, Set(Oids.ophOrganisaatioOid, oid)))
    targetAncestors.exists { x => user.username == x || allowedOrgs.contains(x) } || komoAuthorization(user, action, target.komo)
  }

  private def komoAuthorization(user:User, action:String, komo:Option[String]): Boolean = {
    komo.exists(user.allowByKomo(_, action))
  }
}

case class Org(oid: String, parent: Option[String], lopetusPvm: Option[DateTime] )

case class AuthorizationSubject[A](item: A, orgs: Set[String], personOid: Option[String], komo: Option[String])

trait AuthorizationSubjectFinder[A] extends Function1[Seq[A], Future[Seq[AuthorizationSubject[A]]]]
