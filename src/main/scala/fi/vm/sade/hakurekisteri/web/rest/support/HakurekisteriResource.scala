package fi.vm.sade.hakurekisteri.web.rest.support

import java.util.UUID

import _root_.akka.actor.{ActorRef, ActorSystem}
import _root_.akka.event.{Logging, LoggingAdapter}
import _root_.akka.pattern.ask
import _root_.akka.util.Timeout
import fi.vm.sade.auditlog.{Changes, Target}
import fi.vm.sade.hakurekisteri.{ResourceCreate, ResourceDelete, ResourceUpdate}
import fi.vm.sade.hakurekisteri.organization._
import fi.vm.sade.hakurekisteri.rest.support._
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import org.scalatra._
import org.scalatra.commands._
import org.scalatra.json.{JacksonJsonSupport, JsonSupport}
import org.scalatra.swagger.SwaggerSupportSyntax.OperationBuilder
import org.scalatra.swagger._
import org.scalatra.validation.{FieldName, ValidationError}

import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.implicitConversions
import scala.util.Try
import scalaz.NonEmptyList

trait HakurekisteriCrudCommands[A <: Resource[UUID, A], C <: HakurekisteriCommand[A]] extends ScalatraServlet with SwaggerSupport { this: HakurekisteriResource[A, C] with SecuritySupport with JsonSupport[_] =>

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
      val res = deleteResource()
      //auditLog(currentUser.get.username, HakurekisteriOperation.RESOURCE_DELETE, resourceName, params("id"))
      audit.log(auditUtil.parseUser(request, currentUser.get.username),
        ResourceDelete,
        new Target.Builder().setField("id", params("id")).build(),
        new Changes.Builder().build())
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
      val user = currentUser.get.username
      res.is.onSuccess {
        case ActionResult(_, r, headers) =>
          val id: String = Try(r.asInstanceOf[A with Identified[UUID]].id.toString).getOrElse(r.toString)
          //auditLog(user, HakuRekisteriOperation.RESOURCE_CREATE, resourceName, id)
          audit.log(auditUtil.parseUser(request, currentUser.get.username),
            ResourceCreate,
            new Target.Builder().setField("id", params("id")).build(),
            new Changes.Builder().build())

      }
      res
    }
  }

  post("/:id", operation(update)) {
    if (!currentUser.exists(_.canWrite(resourceName))) throw UserNotAuthorized("not authorized")
    else {
      val updated = updateResource()
      //auditLog(currentUser.get.username, HakuRekisteriOperation.RESOURCE_UPDATE, resourceName, params("id"))
      audit.log(auditUtil.parseUser(request, currentUser.get.username),
        ResourceUpdate,
        new Target.Builder().setField("id", params("id")).build(),
        new Changes.Builder().build())

      updated
    }
  }


  /*protected def auditAddMuutokset(muutokset: Map[String, (String, String)])(builder: LogMessageBuilder): LogMessageBuilder = {
    muutokset.foreach {
      case (kentta, (vanhaArvo, uusiArvo)) => builder.add(kentta, vanhaArvo, uusiArvo)
    }
    builder
  }

  protected def auditLog(username: String, operation: Operation, resourceName: String, resourceId: String, muutokset: Map[String, (String, String)] = Map()) =
    audit.log(auditAddMuutokset(muutokset)(LogMessage.builder())
      .id(username)
      .setOperaatio(operation)
      .setResourceName(resourceName)
      .setResourceId(resourceId)
      .build())*/

  def updateResource(): Object = {
    Try(UUID.fromString(params("id"))).map(updateResource(_, currentUser)).get
  }

  get("/:id", operation(read)) {
    if (!currentUser.exists(_.canRead(resourceName))) throw UserNotAuthorized("not authorized")
    else getResource
  }

  def getResource: Object = {
    Try(UUID.fromString(params("id"))).map(id => readResource(AuthorizedRead(id, currentUser.get))).get
  }

  get("/", operation(query))(
    if (!currentUser.exists(_.canRead(resourceName))) throw UserNotAuthorized("not authorized")
    else {
      val t0 = Platform.currentTime
      queryResource(currentUser, t0)
    }
  )

  incident {
    case t: MalformedResourceException => (id) => BadRequest(IncidentReport(id, t.getMessage))
    case t: IllegalArgumentException => (id) => BadRequest(IncidentReport(id, t.getMessage))
  }
}

case class NotFoundException(resource: String) extends Exception(resource)
case class UserNotAuthorized(message: String) extends Exception(message)

abstract class HakurekisteriResource[A <: Resource[UUID, A], C <: HakurekisteriCommand[A]]
(actor: ActorRef, qb: Map[String, String] => Query[A])
(implicit val security: Security, sw: Swagger, system: ActorSystem, mf: Manifest[A], cf: Manifest[C])
  extends HakuJaValintarekisteriStack with HakurekisteriJsonSupport with JacksonJsonSupport with SwaggerSupport with FutureSupport with HakurekisteriParsing[A]
  with QueryLogging with SecuritySupport {

  override val logger: LoggingAdapter = Logging.getLogger(system, this)

  case class MalformedResourceException(errors: NonEmptyList[ValidationError]) extends Exception {
    override def getMessage: String = {
      val messages: NonEmptyList[String] = for (
        error <- errors
      ) yield s"${error.field.map{case FieldName(field) => s"problem with $field: "}.getOrElse("problem: ")} ${error.message}"
      messages.list.toList.mkString(", ")
    }
  }

  def className[CL](implicit m: Manifest[CL]) = m.runtimeClass.getSimpleName

  lazy val resourceName = className[A]

  protected implicit def executor: ExecutionContext = system.dispatcher
  val timeOut = 120
  implicit val defaultTimeout: Timeout = timeOut.seconds

  class FutureActorResult[B: Manifest](message: Future[AnyRef], success: (B) => AnyRef) extends AsyncResult() {
    override implicit def timeout: Duration = timeOut.seconds
    val is = message.flatMap(actor ? _).flatMap{
      case Some(res: B) => Future.successful(success(res))
      case None => Future.failed(new NotFoundException(""))
      case a : B => Future.successful(success(a))
    }

  }

  class ActorResult[B: Manifest](message: AnyRef, success: (B) => AnyRef) extends FutureActorResult[B](Future.successful(message), success)

  def createEnabled(resource: A, user: Option[User]) = Future.successful(true)

  def notEnabled = new Exception("operation not enabled")

  def createResource(user: Option[User]) = {
    val msg: Future[AuthorizedCreate[A, UUID]] = (command[C] >> (_.toValidatedResource(user.get.username))).flatMap(_.fold(
      errors => Future.failed(MalformedResourceException(errors)),
      (resource: A) =>
        createEnabled(resource, user).flatMap(enabled =>
          if (enabled) Future.successful(AuthorizedCreate[A, UUID](resource, user.get))
          else Future.failed(ResourceNotEnabledException)
        )
    ))
    new FutureActorResult(msg, ResourceCreated(request.getRequestURL))
  }

  object ResourceCreated {
    private def postfixBaseUri(baseUri: StringBuffer): StringBuffer = baseUri match {
      case s: StringBuffer if s.length() == 0 || s.charAt(s.length() - 1) != '/' => s.append("/")
      case _ => baseUri
    }
    def apply(baseUri: StringBuffer)(createdResource: A with Identified[UUID]) = Created(createdResource, headers = Map("Location" -> postfixBaseUri(baseUri).append(createdResource.id).toString))
  }

  def identifyResource(resource : A, id: UUID): A with Identified[UUID] = resource.identify(id)

  def updateEnabled(resource: A, user: Option[User]) = Future.successful(true)

  def updateResource(id: UUID, user: Option[User]): Object = {
    val myCommand: C = command[C]

    val msg = (actor ? id).mapTo[Option[A]].flatMap((a: Option[A]) => a  match {
      case Some(res) =>
        updateEnabled(res, user).flatMap(enabled =>
          if (enabled) {
            (myCommand >> (_.toValidatedResource(user.get.username))).flatMap(
              _.fold(
                errors => Future.failed(MalformedResourceException(errors)),
                resource => Future.successful(AuthorizedUpdate[A, UUID](identifyResource(resource, id), user.get)))
            )
          } else {
            Future.failed(ResourceNotEnabledException)
          })
      case None => {
        Future.failed(NotFoundException(id.toString))
      }
    })

    new FutureActorResult[A with Identified[UUID]](msg, Ok(_))
  }

  def deleteResource(id: UUID, user: Option[User]): Object = {
    new ActorResult[Boolean](AuthorizedDelete(id,  user.get), (unit) => Ok())
  }

  def readResource(withRef: AnyRef): AsyncResult = {
    new ActorResult[Option[A with Identified[UUID]]](withRef, {
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

object ResourceNotEnabledException extends Exception
