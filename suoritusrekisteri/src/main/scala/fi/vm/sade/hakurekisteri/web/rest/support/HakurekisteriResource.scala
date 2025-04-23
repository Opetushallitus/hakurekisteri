package fi.vm.sade.hakurekisteri.web.rest.support

import java.util.UUID

import _root_.akka.actor.{ActorRef, ActorSystem}
import _root_.akka.event.{Logging, LoggingAdapter}
import _root_.akka.pattern.ask
import _root_.akka.util.Timeout
import fi.vm.sade.auditlog.{Changes, Target}
import fi.vm.sade.hakurekisteri._
import fi.vm.sade.hakurekisteri.organization._
import fi.vm.sade.hakurekisteri.rest.support._
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import org.json4s.JsonAST.{JNull, JString}
import org.json4s.{DefaultFormats, Formats, JValue}
import org.scalatra._
import org.scalatra.i18n.I18nSupport
import org.scalatra.json.{JacksonJsonSupport, JsonSupport}
import org.scalatra.swagger.SwaggerSupportSyntax.OperationBuilder
import org.scalatra.swagger._

import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions
import scala.util.Try
import scalaz.NonEmptyList

trait HakurekisteriCrudCommands[A <: Resource[UUID, A]]
    extends ScalatraServlet
    with SwaggerSupport { this: HakurekisteriResource[A] with SecuritySupport with JsonSupport[_] =>

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
    else {
      audit.log(
        auditUser,
        ResourceDelete,
        new Target.Builder()
          .setField("resource", resourceName)
          .setField("id", params("id"))
          .build(),
        Changes.EMPTY
      )
      val res = deleteResource()
      res
    }
  }

  def deleteResource(): Object = {
    Try(UUID.fromString(params("id"))).map(deleteResource(_, currentUser)).get
  }

  post("/", operation(create)) {
    if (!currentUser.exists(_.canWrite(resourceName))) throw UserNotAuthorized("not authorized")
    else {
      val res = createResource(currentUser)
      val au = auditUser
      res.is.foreach { case ActionResult(_, r, headers) =>
        logger.info("Action success")
        val id = Try(r.asInstanceOf[A with Identified[UUID]].id.toString).getOrElse(r.toString)
        audit.log(
          au,
          ResourceCreate,
          new Target.Builder()
            .setField("resource", resourceName)
            .setField("id", id)
            .build(),
          Changes.EMPTY
        )
      }
      res
    }
  }
  post("/:id", operation(update)) {
    if (!currentUser.exists(_.canWrite(resourceName))) throw UserNotAuthorized("not authorized")
    else {
      val updated: Object = updateResource()
      audit.log(
        auditUser,
        ResourceUpdate,
        new Target.Builder()
          .setField("resource", resourceName)
          .setField("id", params("id"))
          .build(),
        Changes.EMPTY
      )
      updated
    }
  }

  def updateResource(): Object = {
    Try(UUID.fromString(params("id"))).map(updateResource(_, currentUser)).get
  }

  get("/:id", operation(read)) {
    if (!currentUser.exists(_.canRead(resourceName))) throw UserNotAuthorized("not authorized")
    else {
      audit.log(
        auditUser,
        ResourceRead,
        AuditUtil
          .targetFromParams(params)
          .setField("resource", resourceName)
          .build(),
        Changes.EMPTY
      )
      getResource
    }
  }

  def getResource: Object = {
    Try(UUID.fromString(params("id")))
      .map(id => readResource(AuthorizedRead(id, currentUser.get)))
      .get
  }

  get("/", operation(query))(
    if (!currentUser.exists(_.canRead(resourceName))) throw UserNotAuthorized("not authorized")
    else {
      audit.log(
        auditUser,
        ResourceReadByQuery,
        AuditUtil
          .targetFromParams(params)
          .setField("resource", resourceName)
          .setField("summary", query.result.summary)
          .build(),
        Changes.EMPTY
      )
      val t0 = Platform.currentTime
      queryResource(currentUser, t0)
    }
  )

  incident {
    case t: MalformedResourceException => (id) => BadRequest(IncidentReport(id, t.getMessage))
    case t: ValidationError            => (id) => BadRequest(IncidentReport(id, t.message))
    case t: IllegalArgumentException   => (id) => BadRequest(IncidentReport(id, t.getMessage))
  }
}

case class NotFoundException(resource: String) extends Exception(resource)
case class UserNotAuthorized(message: String) extends Exception(message)

abstract class HakurekisteriResource[A <: Resource[UUID, A]](
  actor: ActorRef,
  qb: Map[String, String] => Query[A]
)(implicit val security: Security, sw: Swagger, system: ActorSystem, mf: Manifest[A])
    extends HakuJaValintarekisteriStack
    with HakurekisteriJsonSupport
    with JacksonJsonSupport
    with SwaggerSupport
    with FutureSupport
    with QueryLogging
    with SecuritySupport
    //with I18nSupport todo fixme kenties: tästä aiheutui ongelmia, jotka korjaantuivat poistolla. En tätä kirjoittaessa tiedä, mitä hyödyllistä tämä tekee, mutta voihan se jotain tehdä.
    with ValidationSupport {

  override val logger: LoggingAdapter = Logging.getLogger(system, this)

  case class ValidationError(
    message: String,
    field: Option[String] = None,
    error: Option[Exception] = None
  ) extends Exception

  case class MalformedResourceException(errors: NonEmptyList[ValidationError]) extends Exception {
    override def getMessage: String = {
      val messages: NonEmptyList[String] =
        for (error <- errors)
          yield s"${error.field.map { s: String => s"problem with $s: " }.getOrElse("problem: ")} ${error.message}"
      messages.list.toList.mkString(", ")
    }
  }

  def className[CL](implicit m: Manifest[CL]) = m.runtimeClass.getSimpleName

  lazy val resourceName: String = className[A]

  def parseResourceFromBody(user: String): Either[ValidationError, A]

  protected implicit def executor: ExecutionContext = system.dispatcher
  val timeOut = 120
  implicit val defaultTimeout: Timeout = timeOut.seconds

  class FutureActorResult[B: Manifest](message: Future[AnyRef], success: (B) => AnyRef)
      extends AsyncResult() {
    override implicit def timeout: Duration = timeOut.seconds
    val is = message.flatMap(actor ? _).flatMap {
      case Some(res: B) => Future.successful(success(res))
      case None         => Future.failed(new NotFoundException(""))
      case a: B         => Future.successful(success(a))
    }

  }

  class ActorResult[B: Manifest](message: AnyRef, success: (B) => AnyRef)
      extends FutureActorResult[B](Future.successful(message), success)

  def createEnabled(resource: A, user: Option[User]) = Future.successful(true)

  def notEnabled = new Exception("operation not enabled")

  def createResource(user: Option[User]) = {
    logger.info("Create; Parsing resource")
    val msg: Future[AuthorizedCreate[A, UUID]] = parseResourceFromBody(user.get.username) match {
      case Left(e) =>
        logger.info("Something went wrong with resource creation: " + e + ", " + e.message)
        Future.failed(e)
      case Right(res) =>
        createEnabled(res, user).flatMap(enabled =>
          if (enabled) {
            logger.info("Going as planned, created resource: " + res)
            Future.successful(AuthorizedCreate[A, UUID](res, user.get))
          } else Future.failed(ResourceNotEnabledException)
        )
    }
    new FutureActorResult(msg, ResourceCreated(request.getRequestURL))
  }

  object ResourceCreated {
    private def postfixBaseUri(baseUri: StringBuffer): StringBuffer = baseUri match {
      case s: StringBuffer if s.length() == 0 || s.charAt(s.length() - 1) != '/' => s.append("/")
      case _                                                                     => baseUri
    }
    def apply(baseUri: StringBuffer)(createdResource: A with Identified[UUID]) = Created(
      createdResource,
      headers = Map("Location" -> postfixBaseUri(baseUri).append(createdResource.id).toString)
    )
  }

  def identifyResource(resource: A, id: UUID): A with Identified[UUID] = resource.identify(id)

  def updateEnabled(resource: A, user: Option[User]) = Future.successful(true)

  def updateResource(id: UUID, user: Option[User]): Object = {
    logger.info("Update; parsing resource.")
    val updatedResource: Either[ValidationError, A] = parseResourceFromBody(
      user.get.username
    ) //Needs to be parsed now instead of later, or implicit request is not available.
    val msg: Future[AuthorizedUpdate[A, UUID]] = (actor ? id)
      .mapTo[Option[A]]
      .flatMap((a: Option[A]) =>
        a match {
          case Some(res) =>
            updateEnabled(res, user).flatMap(enabled =>
              if (enabled) {
                updatedResource match {
                  case Left(e) =>
                    logger.info("Something went wrong with resource creation: " + e)
                    Future.failed(e)
                  case Right(res) =>
                    logger.info("Going as planned, updated resource: " + res)
                    Future
                      .successful(AuthorizedUpdate[A, UUID](identifyResource(res, id), user.get))
                }
              } else {
                Future.failed(ResourceNotEnabledException)
              }
            )
          case None =>
            Future.failed(NotFoundException(id.toString))
        }
      )
    new FutureActorResult[A with Identified[UUID]](msg, Ok(_))
  }

  def deleteResource(id: UUID, user: Option[User]): Object = {
    new ActorResult[Boolean](AuthorizedDelete(id, user.get), (unit) => Ok())
  }

  def readResource(withRef: AnyRef): AsyncResult = {
    new ActorResult[Option[A with Identified[UUID]]](
      withRef,
      {
        case Some(data) => Ok(data)
        case None       => NotFound()
      }
    )
  }

  def queryResource(user: Option[User], t0: Long): Product with Serializable = {
    (Try(qb(params.toMap)) map ((q: Query[A]) => ResourceQuery(q, user, t0)) recover {
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

  protected implicit def swagger: SwaggerEngine = sw
}

object ResourceNotEnabledException extends Exception
