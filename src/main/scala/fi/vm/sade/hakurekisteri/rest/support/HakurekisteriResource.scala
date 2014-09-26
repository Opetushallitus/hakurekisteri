package fi.vm.sade.hakurekisteri.rest.support

import fi.vm.sade.hakurekisteri.HakuJaValintarekisteriStack
import org.scalatra.swagger._
import org.scalatra.json.{JsonSupport, JacksonJsonSupport}
import scala.concurrent.ExecutionContext
import _root_.akka.util.Timeout
import _root_.akka.actor.{ActorRef, ActorSystem}
import org.scalatra._
import _root_.akka.pattern.ask
import org.scalatra.swagger.SwaggerSupportSyntax.OperationBuilder
import scala.concurrent.duration.Duration
import scala.util.Try
import fi.vm.sade.hakurekisteri.storage.Identified
import scala.concurrent.duration._
import javax.servlet.http.HttpServletRequest
import org.springframework.security.core.Authentication

import org.scalatra.commands._
import java.util.UUID
import fi.vm.sade.hakurekisteri.organization._
import scala.util.matching.Regex
import org.springframework.security.cas.authentication.CasAuthenticationToken
import org.jasig.cas.client.authentication.AttributePrincipal
import fi.vm.sade.hakurekisteri.organization.AuthorizedRead
import fi.vm.sade.hakurekisteri.organization.AuthorizedQuery
import fi.vm.sade.hakurekisteri.organization.AuthorizedCreate
import fi.vm.sade.hakurekisteri.organization.AuthorizedDelete

trait HakurekisteriCrudCommands[A <: Resource[UUID], C <: HakurekisteriCommand[A]] extends ScalatraServlet with SwaggerSupport { this: HakurekisteriResource[A , C] with SecuritySupport with JsonSupport[_] =>

  before() {
    contentType = formats("json")
  }

  val create: OperationBuilder
  val update: OperationBuilder
  val query: OperationBuilder
  val read: OperationBuilder
  val delete: OperationBuilder

  delete("/:id", operation(delete)) {
    if (!hasAnyRoles(currentUser, Seq("CRUD"))) throw UserNotAuthorized("not authorized")
    else deleteResource
  }

  def deleteResource: Object = {
    Try(UUID.fromString(params("id"))).map(deleteResource(_, getKnownOrganizations(currentUser), currentUser.map(_.username))).get
  }

  post("/", operation(create)) {
    if (!hasAnyRoles(currentUser, Seq("CRUD", "READ_UPDATE"))) throw UserNotAuthorized("not authorized")
    else createResource(getKnownOrganizations(currentUser), currentUser.map(_.username))
  }

  post("/:id", operation(update)) {
    if (!hasAnyRoles(currentUser, Seq("CRUD", "READ_UPDATE"))) throw UserNotAuthorized("not authorized")
    else updateResource
  }

  def updateResource: Object = {
    Try(UUID.fromString(params("id"))).map(updateResource(_, getKnownOrganizations(currentUser), currentUser.map(_.username))).get
  }

  get("/:id", operation(read)) {
    if (!hasAnyRoles(currentUser, Seq("CRUD", "READ_UPDATE", "READ"))) throw UserNotAuthorized("not authorized")
    else getResource
  }

  def getResource: Object = {
    Try(UUID.fromString(params("id"))).map(readResource(_, getKnownOrganizations(currentUser), currentUser.map(_.username))).get
  }

  get("/", operation(query))(
    if (!hasAnyRoles(currentUser, Seq("CRUD", "READ_UPDATE", "READ"))) throw UserNotAuthorized("not authorized")
    else queryResource(getKnownOrganizations(currentUser), currentUser.map(_.username))
  )

  case class NotFoundException() extends Exception

  notFound {
    throw NotFoundException()
  }

  incident {
    case t: NotFoundException => (id) => NotFound(IncidentReport(id, "resource not found"))
    case t: MalformedResourceException => (id) => BadRequest(IncidentReport(id, t.getMessage))
    case t: UserNotAuthorized => (id) => Forbidden(IncidentReport(id, "not authorized"))
    case t: IllegalArgumentException => (id) => BadRequest(IncidentReport(id, t.getMessage))
  }
}

abstract class  HakurekisteriResource[A <: Resource[UUID], C <: HakurekisteriCommand[A]](actor: ActorRef, qb: Map[String,String] => Query[A])(implicit sw: Swagger, system: ActorSystem, mf: Manifest[A],cf:Manifest[C]) extends HakuJaValintarekisteriStack with HakurekisteriJsonSupport with JacksonJsonSupport with SwaggerSupport with FutureSupport with JacksonJsonParsing with CorsSupport {

  options("/*") {
    response.setHeader("Access-Control-Allow-Headers", request.getHeader("Access-Control-Request-Headers"))
  }

  case class UserNotAuthorized(message: String) extends Exception(message)
  case class MalformedResourceException(message: String) extends Exception(message)

  protected implicit def executor: ExecutionContext = system.dispatcher
  val timeOut = 120
  implicit val defaultTimeout: Timeout = timeOut.seconds

  class ActorResult[B: Manifest](message: AnyRef, success: (B) => AnyRef) extends AsyncResult() {
    override implicit def timeout: Duration = timeOut.seconds
    val is = (actor ? message).mapTo[B].
      map(success)
  }

  def createResource(authorities: Seq[String], user: Option[String]): Object = {
    (command[C] >> (_.toValidatedResource(user.get))).fold(
      errors => throw MalformedResourceException(errors.toString()),
      resource => new ActorResult(AuthorizedCreate(resource, authorities, user.getOrElse("anonymous")), ResourceCreated(request.getRequestURL)))
  }

  object ResourceCreated {
    def apply(baseUri: StringBuffer)(createdResource: A with Identified[UUID]) = Created(createdResource, headers = Map("Location" -> baseUri.append("/").append(createdResource.id).toString))
  }

  def identifyResource(resource : A, id: UUID): A with Identified[UUID] = resource.identify(id)

  def updateResource(id: UUID, authorities: Seq[String], user: Option[String]): Object = {
    (command[C] >> (_.toValidatedResource(user.get))).fold(
      errors => throw MalformedResourceException(errors.toString()),
      resource => new ActorResult[A with Identified[UUID]](AuthorizedUpdate(identifyResource(resource, id), authorities, user.getOrElse("anonymous")), Ok(_)))
  }

  def deleteResource(id: UUID, authorities: Seq[String], user: Option[String]): Object = {
    new ActorResult[Unit](AuthorizedDelete(id, authorities, user.getOrElse("anonymous")), (unit) => Ok())
  }

  def readResource(id: UUID, authorities: Seq[String], user: Option[String]): Object = {
    new ActorResult[Option[A with Identified[UUID]]](AuthorizedRead(id, authorities, user.getOrElse("anonymous")), {
      case Some(data) => Ok(data)
      case None => NotFound()
    })
  }

  def queryResource(authorities: Seq[String], user: Option[String]): Product with Serializable = {
    (Try(qb(params)) map ((q: Query[A]) => ResourceQuery(q, authorities,user)) recover {
      case e: Exception => logger.warn("Bad query: " + params, e); throw new IllegalArgumentException("illegal query params")
    }).get
  }

  case class ResourceQuery[R](query: Query[R], authorities: Seq[String], user: Option[String]) extends AsyncResult {
    override implicit def timeout: Duration = timeOut.seconds
    val is = {
      val future = (actor ? AuthorizedQuery(query, authorities, user.getOrElse("anonymous"))).mapTo[Seq[R with Identified[UUID]]]
      future.map(Ok(_))
    }
  }

   protected implicit def swagger: SwaggerEngine[_] = sw
}

case class User(username: String, authorities: Seq[String], attributePrincipal: Option[AttributePrincipal])

trait SecuritySupport {
  def currentUser(implicit request: HttpServletRequest): Option[User]

  def getKnownOrganizations(user: Option[User]): Seq[String] = {
    user.map(_.authorities.
      map(splitAuthority).
      filter(isSuoritusRekisteri).
      map(_.reverse.head).
      filter(isOid).toSet.toList
    ).getOrElse(Seq())
  }

  val regex = new Regex("\\d+\\.\\d+\\.\\d+\\.\\d+\\.\\d+\\.\\d+")

  def isOid(x: String) = {
    (regex findFirstIn x).nonEmpty
  }

  def isSuoritusRekisteri: (Seq[String]) => Boolean = {
    _.containsSlice(Seq("ROLE", "APP", "SUORITUSREKISTERI"))
  }

  def splitAuthority(authority: String) = Seq(authority split "_": _*)

  def hasAnyRoles(user: Option[User], roles: Seq[String]): Boolean = user match {
    case Some(u) => roles.map((role) => u.authorities.contains(s"ROLE_APP_SUORITUSREKISTERI_$role")).reduceLeft(_ || _)
    case _ => false
  }
}

trait SpringSecuritySupport extends SecuritySupport {
  import scala.collection.JavaConverters._

  def currentUser(implicit request: HttpServletRequest): Option[User] = {
    val name = Option(request.getUserPrincipal).map(_.getName)
    val authorities = Try(request.getUserPrincipal.asInstanceOf[Authentication].getAuthorities.asScala.toList.map(_.getAuthority))
    val attributePrincipal: Option[AttributePrincipal] = Try(request.getUserPrincipal.asInstanceOf[CasAuthenticationToken].getAssertion.getPrincipal).toOption
    name.map(User(_, authorities.getOrElse(Seq()), attributePrincipal))
  }
}
