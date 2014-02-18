package fi.vm.sade.hakurekisteri.organization

import scala.xml.{Elem, NodeSeq}
import org.scalatra.util.RicherString
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import scala.concurrent.Future
import dispatch._
import Defaults._
import akka.actor.{ActorRef, Actor}
import fi.vm.sade.hakurekisteri.rest.support.Query
import java.util.UUID
import akka.util.Timeout
import java.util.concurrent.TimeUnit
import fi.vm.sade.hakurekisteri.storage.Identified


class OrganizationHierarchy[A:Manifest](filteredActor:ActorRef, organizationFinder: A => String) extends Actor {

  implicit val timeout: akka.util.Timeout = Timeout(30, TimeUnit.SECONDS)

  implicit def nodeSeq2RicherString(ns:NodeSeq):RicherString  = new RicherString(ns.text)
  val svc = url("http://luokka.hard.ware.fi:8301/organisaatio-service/services/organisaatioService").POST
  var authorizer = OrganizationAuthorizer(Map())


  fetch()

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

  import akka.pattern.pipe

  def fetch() {
    println("fetching organizations")
    val result = Http(svc << <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:typ="http://model.api.organisaatio.sade.vm.fi/types">
      <soapenv:Header/> <soapenv:Body>
        <typ:getOrganizationStructure></typ:getOrganizationStructure>
      </soapenv:Body>
    </soapenv:Envelope>.toString)
    val soapFuture: Future[Elem] = result map ((response) => scala.xml.XML.load(new java.io.InputStreamReader(response.getResponseBodyAsStream, "UTF-8")))
    val orgTagsFuture = soapFuture map (_ \ "Body" \ "getOrganizationStructureResponse" \ "organizationStructure")
    val orgsFuture =  orgTagsFuture map  (_.map((org) => Org((org \ "@oid").blankOption.get, (org \ "@parentOid").blankOption, (org \ "@lakkautusPvm").blankOption.map((pvm) => {DateTime.parse(pvm, DateTimeFormat.forPattern("yyyy-MM-dddZ"))}))))
    def orgPaths = orgsFuture map ((found) => (Map[String, Seq[String]]() /: found) ((m,org) => addParentToPaths(addSelfToPaths(m,org),org)))
    def authorizer = orgPaths map (OrganizationAuthorizer(_))

    authorizer pipeTo self

  }




  import akka.pattern.ask
  override def receive: Receive = {
    case a:OrganizationAuthorizer => println("org paths loaded");authorizer = a
    case AuthorizedQuery(q,orgs) => (filteredActor ? q).map((item) => {println(item);item}).mapTo[Seq[A with Identified]].map(_.filter((item) => authorizer.checkAccess(orgs, organizationFinder(item)))) pipeTo sender
    case AuthorizedRead(id, orgs) => (filteredActor ? id).mapTo[Option[A with Identified]].map(_.flatMap((item) => if (authorizer.checkAccess(orgs, organizationFinder(item))) Some(item) else None)) pipeTo sender
    case message:AnyRef => filteredActor forward message
  }

}

case class AuthorizedQuery[A](q:Query[A], orgs: Seq[String])
case class AuthorizedRead(id:UUID, orgs:Seq[String])

case class OrganizationAuthorizer(orgPaths: Map[String, Seq[String]]) {
  def checkAccess(user:Seq[String], target:String) = {
    val path = orgPaths.getOrElse(target, Seq())
    println("path " + path)
    println("user " + user)
    path.exists { x => user.contains(x) }
  }
}
case class Org(oid:String, parent:Option[String], lopetusPvm: Option[DateTime] )
