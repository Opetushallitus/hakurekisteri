package fi.vm.sade.hakurekisteri.rest.support

import fi.vm.sade.hakurekisteri.HakuJaValintarekisteriStack
import org.scalatra.swagger._
import org.scalatra.json.JacksonJsonSupport
import scala.concurrent.{Future, ExecutionContext}
import _root_.akka.util.Timeout
import _root_.akka.actor.{ActorRef, ActorSystem}
import org.scalatra._
import _root_.akka.pattern.ask
import org.scalatra.swagger.SwaggerSupportSyntax.OperationBuilder
import scala.util.Try
import fi.vm.sade.hakurekisteri.storage.Identified
import java.util.concurrent.TimeUnit
import org.springframework.security.core.GrantedAuthority
import javax.servlet.http.HttpServletRequest
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.Authentication


abstract class   HakurekisteriResource[A](actor:ActorRef, qb: Map[String,String] => Query[A])(implicit sw: Swagger, system: ActorSystem, mf: Manifest[A])extends HakuJaValintarekisteriStack with HakurekisteriJsonSupport with JacksonJsonSupport with SwaggerSupport with FutureSupport {

  protected implicit def executor: ExecutionContext = system.dispatcher

  val timeout = 10

  implicit val defaultTimeout = Timeout(timeout, TimeUnit.SECONDS)

  before() {
    contentType = formats("json")
  }

  def create(op: OperationBuilder) {
    post("/", operation(op)) {


      new AsyncResult() {
        val is = actor ? parsedBody.extract[A]
      }
    }
  }


  /*post("/:id") {
    new AsyncResult() {
      val is:
    }

  } */

  implicit val queryBuilder: (Map[String, String]) => Query[A] = qb

  def read(op: OperationBuilder) (implicit pb: Map[String, String] => Query[A]) {
    get("/", operation(op))(
      (Try(pb(params)) map ((q: Query[A]) => ResourceQuery(q)) recover {
        case e: Exception => logger.warn("Bad query: " + params ,e);BadRequest("Illegal Query")
      }).get
    )
  }



  case class ResourceQuery[R](query: Query[R]) extends AsyncResult {

    val is:Future[Seq[R with Identified]] = (actor ? query).mapTo[Seq[R with Identified]]
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
