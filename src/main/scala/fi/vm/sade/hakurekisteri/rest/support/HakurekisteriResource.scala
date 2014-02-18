package fi.vm.sade.hakurekisteri.rest.support

import fi.vm.sade.hakurekisteri.HakuJaValintarekisteriStack
import org.scalatra.swagger._
import org.scalatra.json.{JsonSupport, JacksonJsonSupport}
import scala.concurrent.{Future, ExecutionContext}
import _root_.akka.util.Timeout
import _root_.akka.actor.{ActorRef, ActorSystem}
import org.scalatra._
import _root_.akka.pattern.ask
import org.scalatra.swagger.SwaggerSupportSyntax.OperationBuilder
import scala.util.Try
import fi.vm.sade.hakurekisteri.storage.Identified
import java.util.concurrent.TimeUnit
import javax.servlet.http.HttpServletRequest
import org.springframework.security.core.Authentication

import org.scalatra.commands._
import java.util.UUID
import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriCommand, Resource}
import org.json4s._
import scala.Some

trait HakurekisteriCrudCommands[A <: Resource, C <: HakurekisteriCommand[A]] extends ScalatraServlet with JsonSupport[JValue] with SwaggerSupport { this: HakurekisteriResource[A , C] =>

  before() {
    contentType = formats("json")
  }

  val create:OperationBuilder
  val update:OperationBuilder
  val query:OperationBuilder

  post("/", operation(create)) {
    println("creating" + request.body)
    createResource
  }

  post("/:id", operation(update)) {
    updateResource
  }

  get("/:id") {
    readResource

  }

  get("/", operation(query))(
    queryResource
  )

}

abstract class   HakurekisteriResource[A <: Resource, C <: HakurekisteriCommand[A]](actor:ActorRef, qb: Map[String,String] => Query[A])(implicit sw: Swagger, system: ActorSystem, mf: Manifest[A],cf:Manifest[C])extends HakuJaValintarekisteriStack with HakurekisteriJsonSupport with JacksonJsonSupport with SwaggerSupport with FutureSupport with  JacksonJsonParsing  {



  protected implicit def executor: ExecutionContext = system.dispatcher

  val timeout = 10

  implicit val defaultTimeout = Timeout(timeout, TimeUnit.SECONDS)



  class ActorResult[B:Manifest](message: AnyRef, success: (B) => AnyRef) extends AsyncResult() {
    val is = (actor ? message).mapTo[B].
      map(success).
      recover { case e:Throwable => logger.warn("failure in actor operation", e);InternalServerError("Operation failed")}
  }

  def createResource: Object = {
    (command[C] >> (_.toValidatedResource)).fold(
      errors => {logger.warn(errors.toString); BadRequest("Malformed Resource")},
      resource => new ActorResult(resource, ResourceCreated(request.getRequestURL)))
  }





  object ResourceCreated {
    def apply(baseUri:StringBuffer)(createdResource: A with Identified) =   Created(createdResource, headers = Map("Location" -> baseUri.append("/").append(createdResource.id).toString))
  }


  def identifyResource(resource : A, id: UUID): A with Identified = {println("identifying: " + id);resource.identify(id)}



  def updateResource: Object = {
    Try(UUID.fromString(params("id"))).map((id) =>

      (command[C] >> (_.toValidatedResource)).fold(
        errors => BadRequest("Malformed Resource + " + errors),
        resource => new ActorResult[A with Identified](identifyResource(resource, id), Ok(_)))
    ).
      recover {
      case e: Exception => logger.warn("unparseable request", e); BadRequest("Not an uuid")
    }.get
  }




  def readResource: Object = {
    Try(UUID.fromString(params("id"))).map((id) =>
      new ActorResult[Option[A with Identified]](id, {
        case Some(data) => Ok(data)
        case None => NotFound()
        case result =>
          logger.warn("unexpected result from actor: " + result)
          InternalServerError()

      })
    ).
      recover {
      case e: Exception => logger.warn("unparseable request", e); BadRequest("Not an uuid")
    }.get
  }




  def queryResource(): Product with Serializable = {
    (Try(qb(params)) map ((q: Query[A]) => ResourceQuery(q)) recover {
      case e: Exception => logger.warn("Bad query: " + params, e); BadRequest("Illegal Query")
    }).get
  }

  case class ResourceQuery[R](query: Query[R]) extends AsyncResult {

    val is = (actor ? query).mapTo[Seq[R with Identified]].map(Ok(_)).
      recover { case e:Throwable => InternalServerError("Operation failed")}
    val oidRegex = "\\d+\\.\\d+\\.\\d+\\.\\d+\\.\\d+\\.\\d+".r

    //val orgs = getOrganizations(User.current)
    println(User.current)
    def getOrganizations(user:Option[User]):Option[Set[String]] = {
      user.map(_.authorities.
        map((authority:String) => Seq(authority.split("_"): _*)).
        filter(_.containsSlice(Seq("ROLE","APP","SUORITUSREKISTERI"))).
        map(_.reverse.head).
        filter(x => oidRegex.pattern.matcher(x).matches).toSet
      )

    }
  }


  case class User(username:String, authorities: Seq[String])

  import scala.collection.JavaConverters._

  object User {

    def current(implicit request:HttpServletRequest):Option[User]  = {
      val name = Option(request.getUserPrincipal).map(_.getName)
      println("name: " + name)
      val authorities = Try(request.getUserPrincipal.asInstanceOf[Authentication].getAuthorities.asScala.toList.map(_.getAuthority))
      println("authorities: " + authorities)
      name.map(User(_, authorities.getOrElse(Seq())))
    }

  }

   protected implicit def swagger: SwaggerEngine[_] = sw
 }
