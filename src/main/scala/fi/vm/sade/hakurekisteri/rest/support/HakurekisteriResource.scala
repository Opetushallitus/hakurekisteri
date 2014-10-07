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
import scala.util.Try
import fi.vm.sade.hakurekisteri.storage.Identified
import scala.concurrent.duration._
import javax.servlet.http.HttpServletRequest
import org.springframework.security.core.{GrantedAuthority, Authentication}

import org.scalatra.commands._
import java.util.UUID
import fi.vm.sade.hakurekisteri.organization._
import org.springframework.security.cas.authentication.CasAuthenticationToken
import org.jasig.cas.client.authentication.AttributePrincipal
import fi.vm.sade.hakurekisteri.organization.AuthorizedRead
import fi.vm.sade.hakurekisteri.organization.AuthorizedQuery
import fi.vm.sade.hakurekisteri.organization.AuthorizedCreate
import fi.vm.sade.hakurekisteri.organization.AuthorizedDelete
import fi.vm.sade.hakurekisteri.arvosana.Arvosana
import fi.vm.sade.hakurekisteri.suoritus.Suoritus
import scala.reflect.ClassTag
import java.security.Principal

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
    if (!currentUser.exists(_.canDelete(resourceName))) throw UserNotAuthorized("not authorized")
    else deleteResource
  }

  def deleteResource: Object = {
    Try(UUID.fromString(params("id"))).map(deleteResource(_, currentUser)).get
  }

  post("/", operation(create)) {
    if (!currentUser.exists(_.canWrite(resourceName))) throw UserNotAuthorized("not authorized")
    else createResource(currentUser)
  }

  post("/:id", operation(update)) {
    if (!currentUser.exists(_.canWrite(resourceName))) throw UserNotAuthorized("not authorized")
    else updateResource
  }

  def updateResource: Object = {
    Try(UUID.fromString(params("id"))).map(updateResource(_, currentUser)).get
  }

  get("/:id", operation(read)) {
    if (!currentUser.exists(_.canRead(resourceName))) throw UserNotAuthorized("not authorized")
    else getResource
  }

  def getResource: Object = {
    Try(UUID.fromString(params("id"))).map(readResource(_, currentUser)).get
  }

  get("/", operation(query))(
    if (!currentUser.exists(_.canRead(resourceName))) throw UserNotAuthorized("not authorized")
    else queryResource(currentUser)
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

  def className[C](implicit m: Manifest[C]) = m.runtimeClass.getSimpleName


  lazy val resourceName = className[A]

  protected implicit def executor: ExecutionContext = system.dispatcher
  val timeOut = 120
  implicit val defaultTimeout: Timeout = timeOut.seconds

  class ActorResult[B: Manifest](message: AnyRef, success: (B) => AnyRef) extends AsyncResult() {
    override implicit def timeout: Duration = timeOut.seconds
    val is = (actor ? message).mapTo[B].
      map(success)
  }

  def createResource(user: Option[User]): Object = {
    (command[C] >> (_.toValidatedResource(user.get.username))).fold(
      errors => throw MalformedResourceException(errors.toString()),
      resource => new ActorResult(AuthorizedCreate(resource, user.get), ResourceCreated(request.getRequestURL)))
  }

  object ResourceCreated {
    def apply(baseUri: StringBuffer)(createdResource: A with Identified[UUID]) = Created(createdResource, headers = Map("Location" -> baseUri.append("/").append(createdResource.id).toString))
  }

  def identifyResource(resource : A, id: UUID): A with Identified[UUID] = resource.identify(id)

  def updateResource(id: UUID, user: Option[User]): Object = {
    (command[C] >> (_.toValidatedResource(user.get.username))).fold(
      errors => throw MalformedResourceException(errors.toString()),
      resource => new ActorResult[A with Identified[UUID]](AuthorizedUpdate(identifyResource(resource, id), user.get), Ok(_)))
  }

  def deleteResource(id: UUID, user: Option[User]): Object = {
    new ActorResult[Unit](AuthorizedDelete(id,  user.get), (unit) => Ok())
  }

  def readResource(id: UUID, user: Option[User]): Object = {
    new ActorResult[Option[A with Identified[UUID]]](AuthorizedRead(id, user.get), {
      case Some(data) => Ok(data)
      case None => NotFound()
    })
  }

  def queryResource(user: Option[User]): Product with Serializable = {
    (Try(qb(params)) map ((q: Query[A]) => ResourceQuery(q, user)) recover {
      case e: Exception => logger.warn("Bad query: " + params, e); throw new IllegalArgumentException("illegal query params")
    }).get
  }

  case class ResourceQuery[R](query: Query[R], user: Option[User]) extends AsyncResult {
    override implicit def timeout: Duration = timeOut.seconds
    val is = {
      val future = (actor ? AuthorizedQuery(query, user.get)).mapTo[Seq[R with Identified[UUID]]]
      future.map(Ok(_))
    }
  }

   protected implicit def swagger: SwaggerEngine[_] = sw
}


