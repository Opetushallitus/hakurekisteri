package fi.vm.sade.hakurekisteri.organization

import scala.xml.Elem
import org.scalatra.util.RicherString._
import org.joda.time.DateTime
import dispatch._
import Defaults._
import akka.actor.{Cancellable, ActorRef, Actor}
import fi.vm.sade.hakurekisteri.rest.support.Query
import java.util.UUID
import fi.vm.sade.hakurekisteri.storage.{DeleteResource, Identified}
import scala.concurrent.duration._
import akka.event.Logging
import com.ning.http.client.Response
import akka.actor.Status.Failure

class OrganizationHierarchy[A:Manifest](serviceUrl:String, filteredActor:ActorRef, organizationFinder: Function1[A,String]) extends FutureOrganizationHierarchy[A](serviceUrl, filteredActor, (item: A) => Future.successful(organizationFinder(item)) )

class FutureOrganizationHierarchy[A:Manifest](serviceUrl:String, filteredActor:ActorRef, organizationFinder: Function1[A, concurrent.Future[String]]) extends OrganizationHierarchyAuthorization[A](serviceUrl, organizationFinder) with Actor {


  val logger = Logging(context.system, this)

  private var scheduledTask: Cancellable = null

  class Update

  object update extends Update

  override def preStart() {
    scheduledTask = context.system.scheduler.schedule(
      0.seconds, 60.minutes,
      self, update)
  }

  override def postStop() {
    scheduledTask.cancel()
  }

  implicit val timeout: akka.util.Timeout = 30.seconds

  def futfilt(s: Seq[A with Identified], authorizer: A with Identified => concurrent.Future[Boolean]) = {
    Future.traverse(s)((item) => authorizer(item).map((_ , item))).map(_.filter(_._1).map(_._2))
  }


  import akka.pattern.ask
  import akka.pattern.pipe
  override def receive: Receive = {
    case a:Update => fetch()
    case a:OrganizationAuthorizer => logger.info("org paths loaded");authorizer = a
    case AuthorizedQuery(q,orgs,_) => (filteredActor ? q).mapTo[Seq[A with Identified]].flatMap((i: Seq[A with Identified]) => futfilt(i, isAuthorized(orgs))) pipeTo sender
    case AuthorizedRead(id, orgs,_) => (filteredActor ? id).mapTo[Option[A with Identified]].flatMap(checkRights(orgs)) pipeTo sender
    case AuthorizedDelete(id, orgs, _)  => val checkedRights = for (resourceToDelete <- (filteredActor ? id);
                                                                    rights <- checkRights(orgs)(resourceToDelete.asInstanceOf[Option[A with Identified]]))
                                                                    yield rights
                                               checkedRights.onSuccess{
                                                              case Some(a) => filteredActor forward DeleteResource(a.id)
                                                              case None => sender ! Unit
                                                             }
                                               checkedRights.onFailure {
                                                              case e => sender ! Failure(e)
                                                             }

    case message:AnyRef => filteredActor forward message
  }


  def checkRights(orgs: Seq[String]) = (item:Option[A with Identified]) => item match {

    case None => Future.successful(None)
    case Some(resource: A with Identified) => isAuthorized(orgs)(resource).map((authorized) => if (authorized) Some(resource) else None)
  }


  def fetch() {
    val authorizer: Future[OrganizationAuthorizer] = createAuthorizer
    logger.info("fetching organizations from: " + serviceUrl)
    authorizer pipeTo self
    authorizer.onFailure {
      case e: Exception => logger.error("failed loading organizations", e)
    }

  }

}

class OrganizationHierarchyAuthorization[A:Manifest](serviceUrl:String, organizationFinder: A => Future[String]) {


  val svc = url(serviceUrl).POST
  var authorizer = OrganizationAuthorizer(Map())


  def addSelfToPaths(m:Map[String,Seq[String]], org:Org) = {
    m + (org.oid -> Seq(org.oid))
  }

  def addParentToPaths(m:Map[String,Seq[String]], org:Org) = {
    val addedParents = org.parent match {
      case None => Map[String,Seq[String]]()
      case Some(parent) => m.filter((t) => t._2.head.equals(org.oid)).map((t) => t._1 -> (parent +: t._2))
    }
    m ++ addedParents
  }






  def createAuthorizer: Future[OrganizationAuthorizer] =  edgeFetch map OrganizationAuthorizer

  def readXml: concurrent.Future[Elem] = {
    val result: dispatch.Future[Response] = Http(svc << <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:typ="http://model.api.organisaatio.sade.vm.fi/types">
      <soapenv:Header/> <soapenv:Body>
        <typ:getOrganizationStructure></typ:getOrganizationStructure>
      </soapenv:Body>
    </soapenv:Envelope>.toString)

    val soapFuture: concurrent.Future[Elem] = result map ((response) => scala.xml.XML.load(new java.io.InputStreamReader(response.getResponseBodyAsStream, "UTF-8")))
    soapFuture
  }

  def possibleEdges(soapFuture: concurrent.Future[Elem]):concurrent.Future[Seq[(Option[String], Option[String])]] = {
    val orgTagsFuture = soapFuture map (_ \ "Body" \ "getOrganizationStructureResponse" \ "organizationStructure")
    orgTagsFuture map (_.map((org) => ((org \ "@parentOid").text.blankOption, (org \ "@oid").text.blankOption)))
  }


  def findEdges(soapFuture: concurrent.Future[Elem]): concurrent.Future[Seq[(String,String)]] = {
    val rawList = possibleEdges(soapFuture)
    rawList.map (_.collect { case (Some(parent:String), Some(child:String)) => (parent,child)} )

  }

  def childOrgs(edges: Seq[(String, String)]): Set[String] = {
    edges.map(_._2).toSet
  }

  def parentOrgs(edges: Seq[(String, String)]): Set[String] = {
    edges.map(_._1).toSet
  }

  def leafOrgs(edges: Seq[(String,String)]): Set[String]= {
    childOrgs(edges) -- parentOrgs(edges)
  }

  def splitWith[T](s:Seq[T], p:T => Boolean ): (Seq[T], Seq[T]) = {
    ((Seq[T](), Seq[T]()) /: s) ((a, item) => if (p(item)) (item +: a._1, a._2) else (a._1, item +: a._2))
  }

  def findNonLeavesAndLeavesLeafEdges(edges: Seq[(String,String)]): (Seq[(String, String)], Seq[(String, String)]) = {
    val leaves = leafOrgs(edges)
    splitWith(edges:Seq[(String,String)], (edge:(String,String)) => leaves.contains(edge._2))
  }

  def findPaths(parentEdges: Seq[(String,String)], leafEdges: Seq[(String,String)], accumulator:Map[String, Seq[String]]): Map[String, Seq[String]] = {
    val leafMap: Map[String, String] = leafEdges.map((edge) => edge._2 -> edge._1).toMap
    val needAddition: Map[String, Seq[String]] = accumulator.map((kv) => {
      val addedPathKeys = kv._2.collect(leafMap)
      val newPath = addedPathKeys ++ kv._2
      kv._1 -> newPath
    }) ++ leafMap.map((kv: (String, String)) => kv._1 -> Seq(kv._2, kv._1))
    val  (newLeaves, newOthers)  = findNonLeavesAndLeavesLeafEdges(parentEdges)
    if (parentEdges.nonEmpty) findPaths(newOthers, newLeaves, needAddition)
    else needAddition ++ leafMap.values.map((v) => v -> Seq(v))
  }

  def edgeBuild(edges:Seq[(String,String)]) = {
    println(edges.filter(_._1 == "1.2.246.562.10.00000000001"))
    findPaths(edges, Seq(), Map())
  }

  def edgeFetch: concurrent.Future[Map[String, Seq[String]]] = {
    val edgeFuture = findEdges(readXml)
    edgeFuture map edgeBuild
  }



  def isAuthorized(orgs: Seq[String])(item: A with Identified): concurrent.Future[Boolean] = authorizer.checkAccess(orgs, organizationFinder(item))


}

case class AuthorizedQuery[A](q:Query[A], orgs: Seq[String], user:String)
case class AuthorizedRead(id:UUID, orgs:Seq[String], user:String)

case class AuthorizedDelete(id:UUID, orgs:Seq[String], user:String)


case class OrganizationAuthorizer(orgPaths: Map[String, Seq[String]]) {
  def checkAccess(user:Seq[String], futTarget:concurrent.Future[String]) = futTarget.map {
    (target) =>
    val path = orgPaths.getOrElse(target, Seq())
    path.exists { x => user.contains(x) }
  }
}
case class Org(oid:String, parent:Option[String], lopetusPvm: Option[DateTime] )
