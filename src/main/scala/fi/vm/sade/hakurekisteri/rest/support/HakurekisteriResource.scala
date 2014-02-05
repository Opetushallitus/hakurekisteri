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


abstract class HakurekisteriResource[A](actor:ActorRef)(implicit system: ActorSystem, mf: Manifest[A])extends HakuJaValintarekisteriStack with HakurekisteriJsonSupport with JacksonJsonSupport with SwaggerSupport with FutureSupport {

  protected implicit def executor: ExecutionContext = system.dispatcher

  val timeout = 10

  implicit val defaultTimeout = Timeout(timeout)

  before() {
    contentType = formats("json")
  }


  post("/") {
    new AsyncResult() {
      val is = actor ? parsedBody.extract[A]
    }
  }

  def parseQuery(params:Params)(implicit pb: Map[String, String] => Query[A]): Either[Exception, Query[A]] = {
    try Right(pb(params))
    catch {case e: Exception => Left(e)}
  }

  val queryResource: (Either[Exception, Query[A]]) => Any = {
    case Right(query) => ResourceQuery(query)
    case Left(e) => BadRequest("Illegal Query")
  }

  def read(op: OperationBuilder) (implicit pb: Map[String, String] => Query[A]) {
    get("/", operation(op))(
      (parseQuery _
        andThen
        queryResource) (params))
  }

  case class ResourceQuery[R](query: Query[R]) extends AsyncResult {
    val is:Future[Seq[R]] = (actor ? query).mapTo[Seq[R]]
  }

}
