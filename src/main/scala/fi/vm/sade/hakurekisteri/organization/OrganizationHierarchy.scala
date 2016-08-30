package fi.vm.sade.hakurekisteri.organization

import java.util.UUID

import akka.actor.{Actor, ActorRef, Cancellable}
import akka.event.Logging
import dispatch.Defaults._
import dispatch._
import fi.vm.sade.hakurekisteri.integration.{HttpConfig, OphUrlProperties, VirkailijaRestClient}
import fi.vm.sade.hakurekisteri.rest.support.{Query, Resource, User}
import fi.vm.sade.hakurekisteri.storage.{DeleteResource, Identified}
import fi.vm.sade.hakurekisteri.{Config, Oids}
import org.joda.time.DateTime

import scala.concurrent.duration._

class OrganizationHierarchy[A <: Resource[I, A] :Manifest, I: Manifest](filteredActor: ActorRef, organizationFinder: (A) => (Set[String],Option[String]), config: Config, organisaatioClient: VirkailijaRestClient)
  extends FutureOrganizationHierarchy[A, I](filteredActor, (item: A) => Future.successful(organizationFinder(item)), config, organisaatioClient = organisaatioClient)

class FutureOrganizationHierarchy[A <: Resource[I, A] :Manifest, I: Manifest ](filteredActor: ActorRef, organizationFinder: (A) => concurrent.Future[(Set[String],Option[String])], config: Config, organisaatioClient: VirkailijaRestClient) extends Actor {
  val authorizer = new OrganizationHierarchyAuthorization[A, I](organizationFinder, config.integrations.organisaatioConfig, organisaatioClient)

  val logger = Logging(context.system, this)
  private var scheduledTask: Cancellable = null

  class Update
  object update extends Update

  override def preStart() {
    scheduledTask = context.system.scheduler.schedule(
      0.seconds, config.integrations.organisaatioCacheHours.hours,
      self, update)
  }

  override def postStop() {
    scheduledTask.cancel()
  }

  implicit val timeout: akka.util.Timeout = 300.seconds

  def futfilt(s: Seq[A], authorizer: A => concurrent.Future[Boolean]) = {
    Future.traverse(s)((item) => authorizer(item).map((_ , item))).map(_.filter(_._1).map(_._2))
  }

  val log = Logging(context.system, this)

  import akka.pattern.{ask, pipe}
  override def receive: Receive = {
    case a:Update => fetch()

    case a:OrganizationAuthorizer =>
      logger.info("org paths loaded"); authorizer.authorizer = a

    case AuthorizedQuery(q, user) =>
      (filteredActor ? q).mapTo[Seq[A with Identified[UUID]]].flatMap(futfilt(_, authorizer.isAuthorized(user, "READ"))) pipeTo sender

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

  def checkRights(user: User, action:String) = (item:Option[A]) => item match {
    case None => Future.successful(None)
    case Some(resource) => authorizer.isAuthorized(user, action)(resource).map((authorized) => if (authorized) Some(resource) else None)
  }

  def fetch() {
    val orgAuth: Future[OrganizationAuthorizer] = authorizer.createAuthorizer
    orgAuth pipeTo self
    orgAuth.onFailure {
      case e: Exception => logger.error(e, "failed loading organizations")
    }
  }
}

class OrganizationHierarchyAuthorization[A <: Resource[I, A] : Manifest, I](organizationFinder: A => Future[(Set[String], Option[String])], httpConfig: HttpConfig, organisaatioClient: VirkailijaRestClient) {
  def className[C](implicit m: Manifest[C]) = m.runtimeClass.getSimpleName
  lazy val resourceName = className[A]
  val subjectFinder = (resource: A) => organizationFinder(resource).map(o => Subject(resourceName, o._1, o._2))
  val svc = url(OphUrlProperties.url("organisaatio-service.soap")).POST <:< Map("Caller-Id" -> "suoritusrekisteri.suoritusrekisteri.backend",
    "clientSubSystemCode" -> "suoritusrekisteri.suoritusrekisteri.backend",
    "CSRF" -> "suoritusrekisteri", "Cookie" -> "CSRF=suoritusrekisteri")
  var authorizer = OrganizationAuthorizer(Map())

  def addSelfToPaths(m: Map[String,Seq[String]], org:Org) = {
    m + (org.oid -> Seq(org.oid))
  }

  def addParentToPaths(m:Map[String,Seq[String]], org:Org) = {
    val addedParents = org.parent match {
      case None => Map[String,Seq[String]]()
      case Some(parent) => m.filter((t) => t._2.head.equals(org.oid)).map((t) => t._1 -> (parent +: t._2))
    }
    m ++ addedParents
  }

  def createAuthorizer: Future[OrganizationAuthorizer] = fetchOrganizationHierarchy map OrganizationAuthorizer

  def readJSON: Future[OrganisaatioHakutulos] = {
    organisaatioClient.readObject[OrganisaatioHakutulos]("organisaatio-service.hierarkia.hae.aktiiviset")(200, httpConfig.httpClientMaxRetries)
  }

  def collectChildrenOids(parent: OrganisaatioPerustieto): List[(String, Set[String])] = {
    if (parent.children.isEmpty) List((parent.oid, parent.parentOidPath.split("/").toSet))
    else {
      val leaves: List[(String, Set[String])] = parent.children.flatMap(child => collectChildrenOids(child))
      val newS: (String, Set[String]) = (parent.oid, parent.parentOidPath.split("/").toSet)
      newS :: leaves
    }
  }

  def fetchOrganizationHierarchy: concurrent.Future[Map[String, Set[String]]] = {
    parseOrganizationHierarchy(readJSON)
  }

  def parseOrganizationHierarchy(hakutulos: Future[OrganisaatioHakutulos]): concurrent.Future[Map[String, Set[String]]] = {
    hakutulos.map((x: OrganisaatioHakutulos) => x.organisaatiot.flatMap((org: OrganisaatioPerustieto) => collectChildrenOids(org))).map(_.toMap)
  }

  def isAuthorized(user:User, action: String)(item: A): concurrent.Future[Boolean] = authorizer.checkAccess(user, action,  subjectFinder(item))
}

case class AuthorizedQuery[A](q: Query[A],  user: User)
case class AuthorizedRead[I](id: I, user: User)

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

case class OrganizationAuthorizer(orgPaths: Map[String, Set[String]]) {
  def checkAccess(user: User, action: String, futTarget: concurrent.Future[Subject]) = futTarget.map {
    (target: Subject) => {
      val allowedOrgs = user.orgsFor(action, target.resource)
      val paths: Set[String] = target.orgs.flatMap((oid) => orgPaths.getOrElse(oid, Seq(Oids.ophOrganisaatioOid, oid)))
      paths.exists { x => user.username == x || allowedOrgs.contains(x) } || komoAuthorization(user, action, target.komo)
    }
  }

  private def komoAuthorization(user:User, action:String, komo:Option[String]): Boolean = {
    komo.exists(user.allowByKomo(_, action))
  }
}

case class Org(oid: String, parent: Option[String], lopetusPvm: Option[DateTime] )
