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
import java.util.concurrent.TimeUnit
import javax.servlet.http.HttpServletRequest
import org.springframework.security.core.Authentication

import org.scalatra.commands._
import java.util.UUID
import org.json4s._
import scala.Some
import fi.vm.sade.hakurekisteri.organization.{AuthorizedRead, AuthorizedQuery, OrganizationHierarchy}
import scala.util.matching.Regex

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
    Try(UUID.fromString(params("id"))).map(updateResource).
      recover {
      case e: Exception => logger.warn("unparseable request", e); BadRequest("Not an uuid")
    }.get
  }

  get("/:id") {
    Try(UUID.fromString(params("id"))).map(readResource).
      recover {
      case e: Exception => logger.warn("unparseable request", e); BadRequest("Not an uuid")
    }.get

  }

  get("/", operation(query))(
    queryResource
  )

  notFound {
    response.setStatus(404)
    response.getWriter.println("Resource not found")

  }

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
      errors => {logger.warn(errors.toString()); BadRequest("Malformed Resource")},
      resource => new ActorResult(resource, ResourceCreated(request.getRequestURL)))
  }





  object ResourceCreated {
    def apply(baseUri:StringBuffer)(createdResource: A with Identified) =   Created(createdResource, headers = Map("Location" -> baseUri.append("/").append(createdResource.id).toString))
  }


  def identifyResource(resource : A, id: UUID): A with Identified = {println("identifying: " + id);resource.identify(id)}



  def updateResource(id:UUID): Object = {
    (command[C] >> (_.toValidatedResource)).fold(
      errors => BadRequest("Malformed Resource + " + errors),
      resource => new ActorResult[A with Identified](identifyResource(resource, id), Ok(_)))


  }




  def readResource(id:UUID): Object = {
    val authorities: Seq[String] = getOrganizations(User.current).map(_.toList).getOrElse(Seq())
    new ActorResult[Option[A with Identified]](AuthorizedRead(id, authorities), {
      case Some(data) => Ok(data)
      case None => NotFound()
      case result =>
        logger.warn("unexpected result from actor: " + result)
        InternalServerError()

    })

  }




  def queryResource: Product with Serializable = {
    (Try(qb(params)) map ((q: Query[A]) => ResourceQuery(q)) recover {
      case e: Exception => logger.warn("Bad query: " + params, e); BadRequest("Illegal Query")
    }).get
  }

  case class ResourceQuery[R](query: Query[R]) extends AsyncResult {
    val authorities: Seq[String] = getOrganizations(User.current).map(_.toList).getOrElse(Seq())

    val is = {
      val future = (actor ? AuthorizedQuery(query, authorities)).mapTo[Seq[R with Identified]]

      future.map(Ok(_)).
        recover {
        case e: Throwable => InternalServerError("Operation failed")
      }
    }


  }


  def getOrganizations(user:Option[User]):Option[Set[String]] = {
    user.map(_.authorities.
      map(splitAuthority).
      filter(isSuoritusRekisteri).
      map(_.reverse.head).
      filter(isOid).toSet
    )

  }


  val regex = new Regex("\\d+\\.\\d+\\.\\d+\\.\\d+\\.\\d+\\.\\d+")


  def isOid(x:String) = {
    println(x)

    (regex findFirstIn x).nonEmpty
  }

  def isSuoritusRekisteri: (Seq[String]) => Boolean = {
    _.containsSlice(Seq("ROLE", "APP", "SUORITUSREKISTERI"))
  }

  def splitAuthority(authority: String) = Seq(authority split "_": _*)


  case class User(username:String, authorities: Seq[String])

  import scala.collection.JavaConverters._

  object User {

    def current(implicit request:HttpServletRequest):Option[User]  = {
      val name = Option(request.getUserPrincipal).map(_.getName)
      val authorities = Try(request.getUserPrincipal.asInstanceOf[Authentication].getAuthorities.asScala.toList.map(_.getAuthority))
      name.map(User(_, authorities.getOrElse(Seq())))
    }

  }

   protected implicit def swagger: SwaggerEngine[_] = sw
 }
