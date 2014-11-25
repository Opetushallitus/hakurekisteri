package fi.vm.sade.hakurekisteri.rest.support

import java.util.UUID
import javax.servlet.http.HttpServletRequest

import akka.actor.{ActorRef, ActorSystem}
import akka.event.{Logging, LoggingAdapter}
import akka.pattern.ask
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.organization.{AuthorizedCreate, AuthorizedDelete, AuthorizedQuery, AuthorizedRead, _}
import fi.vm.sade.hakurekisteri.storage.Identified
import org.scalatra._
import org.scalatra.commands._
import org.scalatra.json.{JacksonJsonValueReaderProperty, JacksonJsonSupport, JsonSupport}
import org.scalatra.servlet.FileItem
import org.scalatra.swagger.SwaggerSupportSyntax.OperationBuilder
import org.scalatra.swagger._
import org.scalatra.util.{MultiMapHeadView, ValueReader}

import scala.collection.immutable
import scala.compat.Platform
import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.duration._
import scala.language.implicitConversions
import scala.util.Try
import scala.util.control.Exception._
import scalaz.NonEmptyList
import org.scalatra.validation.{FieldName, ValidationError}

trait HakurekisteriCrudCommands[A <: Resource[UUID, A], C <: HakurekisteriCommand[A]] extends ScalatraServlet with SwaggerSupport { this: HakurekisteriResource[A , C] with SecuritySupport with JsonSupport[_] =>

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
    else {
      val t0 = Platform.currentTime
      queryResource(currentUser, t0)
    }
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

case class UserNotAuthorized(message: String) extends Exception(message)

abstract class  HakurekisteriResource[A <: Resource[UUID, A], C <: HakurekisteriCommand[A]](actor: ActorRef, qb: Map[String,String] => Query[A])(implicit sw: Swagger, system: ActorSystem, mf: Manifest[A],cf:Manifest[C]) extends HakuJaValintarekisteriStack with HakurekisteriJsonSupport with JacksonJsonSupport with SwaggerSupport with FutureSupport with HakurekisteriParsing[A] with CorsSupport with QueryLogging {


  override val logger: LoggingAdapter = Logging.getLogger(system, this)

  options("/*") {
    response.setHeader("Access-Control-Allow-Headers", request.getHeader("Access-Control-Request-Headers"))
  }

  case class MalformedResourceException(errors: NonEmptyList[ValidationError]) extends Exception {
    override def getMessage: String = {
      val messages: NonEmptyList[String] = for (
        error <- errors
      ) yield s"${error.field.map{case FieldName(field) => s"problem with $field: "}.getOrElse("problem: ")} ${error.message}"
      messages.list.mkString(", ")
    }
  }

  def className[C](implicit m: Manifest[C]) = m.runtimeClass.getSimpleName

  lazy val resourceName = className[A]

  protected implicit def executor: ExecutionContext = system.dispatcher
  val timeOut = 120
  implicit val defaultTimeout: Timeout = timeOut.seconds

  class FutureActorResult[B: Manifest](message: Future[AnyRef], success: (B) => AnyRef) extends AsyncResult() {
    override implicit def timeout: Duration = timeOut.seconds
    val is = message.flatMap(actor ? _).mapTo[B].
      map(success)

  }

  class ActorResult[B: Manifest](message: AnyRef, success: (B) => AnyRef) extends FutureActorResult[B](Future.successful(message), success)

  def createResource(user: Option[User]): Object = {
    val msg = (command[C] >> (_.toValidatedResource(user.get.username))).flatMap(
      _.fold(
        errors => Future.failed(MalformedResourceException(errors)),
        resource => Future.successful(AuthorizedCreate[A,UUID](resource, user.get)))
    )

    new FutureActorResult(msg , ResourceCreated(request.getRequestURL))
  }

  object ResourceCreated {
    private def postfixBaseUri(baseUri: StringBuffer): StringBuffer = baseUri match {
      case s: StringBuffer if s.length() == 0 || s.charAt(s.length() - 1) != '/' => s.append("/")
      case _ => baseUri
    }
    def apply(baseUri: StringBuffer)(createdResource: A with Identified[UUID]) = Created(createdResource, headers = Map("Location" -> postfixBaseUri(baseUri).append(createdResource.id).toString))
  }

  def identifyResource(resource : A, id: UUID): A with Identified[UUID] = resource.identify(id)

  def updateResource(id: UUID, user: Option[User]): Object = {
    val msg: Future[AuthorizedUpdate[A, UUID]] = (command[C] >> (_.toValidatedResource(user.get.username))).flatMap(
      _.fold(
        errors => Future.failed(MalformedResourceException(errors)),
        resource => Future.successful(AuthorizedUpdate[A,UUID](identifyResource(resource, id), user.get)))
    )

    new FutureActorResult[A with Identified[UUID]](msg , Ok(_))
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

  def queryResource(user: Option[User], t0: Long): Product with Serializable = {
    (Try(qb(params)) map ((q: Query[A]) => ResourceQuery(q, user, t0)) recover {
      case e: Exception =>
        logger.error(e, "Bad query: " + params)
        throw new IllegalArgumentException("illegal query params")
    }).get
  }

  case class ResourceQuery[R](query: Query[R], user: Option[User], t0: Long) extends AsyncResult {
    override implicit def timeout: Duration = timeOut.seconds
    val is = {
      val future = (actor ? AuthorizedQuery(query, user.get)).mapTo[Seq[R with Identified[UUID]]]
      logQuery(query, t0, future)
      future.map(Ok(_))
    }
  }

  protected implicit def swagger: SwaggerEngine[_] = sw
}




