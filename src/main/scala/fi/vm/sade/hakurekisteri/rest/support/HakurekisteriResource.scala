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
import scala.util.{Failure, Success, Try}
import fi.vm.sade.hakurekisteri.storage.Identified
import java.util.concurrent.TimeUnit


abstract class HakurekisteriResource[A](actor:ActorRef)(implicit system: ActorSystem, mf: Manifest[A])extends HakuJaValintarekisteriStack with HakurekisteriJsonSupport with JacksonJsonSupport with SwaggerSupport with FutureSupport {

  protected implicit def executor: ExecutionContext = system.dispatcher

  val timeout = 10

  implicit val defaultTimeout = Timeout(timeout, TimeUnit.SECONDS)

  before() {
    contentType = formats("json")
  }


  post("/") {
    new AsyncResult() {
      val is = actor ? parsedBody.extract[A]
    }
  }

  def read(op: OperationBuilder) (implicit pb: Map[String, String] => Query[A]) {
    get("/", operation(op))(
      (Try(pb(params)) map ((q: Query[A]) => ResourceQuery(q)) recover {
        case _: Exception => BadRequest("Illegal Query")
      }).get
    )
  }

  case class ResourceQuery[R](query: Query[R]) extends AsyncResult {
    val is:Future[Seq[R with Identified]] = (actor ? query).mapTo[Seq[R with Identified]]

    is.onComplete((r:Try[Seq[R with Identified]]) => println(r.get.head.id))
  }

}
