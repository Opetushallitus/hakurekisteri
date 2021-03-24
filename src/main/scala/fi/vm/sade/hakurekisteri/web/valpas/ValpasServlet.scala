package fi.vm.sade.hakurekisteri.web.valpas

import akka.actor.ActorSystem
import akka.actor.Status.Success
import akka.event.{Logging, LoggingAdapter}
import fi.vm.sade.hakurekisteri.integration.valpas.{ValpasHakemus, ValpasIntergration, ValpasQuery}
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.web.rest.support.{Security, SecuritySupport, UserNotAuthorized}
import org.json4s.{DefaultFormats, Formats}
import org.scalatra.{ActionResult, AsyncResult, BadRequest, FutureSupport, InternalServerError}
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.{Swagger, SwaggerEngine, SwaggerSupport, SwaggerSupportSyntax}

import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

trait ValpasSwaggerApi extends SwaggerSupport {

  val fetchValpasDataForPersons: SwaggerSupportSyntax.OperationBuilder =
    apiOperation[Seq[ValpasHakemus]]("fetchValpasDataForPersons")
      .summary("Hakijoille Valpas-tiedot")
      .description("Palauttaa hakijoiden oppijanumeroille Valpas-tiedot")
      .parameter(
        bodyParam[Seq[String]]("hakijaOids").description("hakijoiden oppijanumerot").required
      )
      .tags("Valpas-resource")

}

class ValpasServlet(valpasIntergration: ValpasIntergration)(implicit
  val sw: Swagger,
  val system: ActorSystem,
  val security: Security
) extends HakuJaValintarekisteriStack
    with JacksonJsonSupport
    with SecuritySupport
    with ValpasSwaggerApi
    with FutureSupport {
  override val logger: LoggingAdapter = Logging.getLogger(system, this)
  override protected implicit def swagger: SwaggerEngine[_] = sw
  override protected def applicationDescription: String = "Valpas-Resource"
  override protected implicit def jsonFormats: Formats = DefaultFormats
  override protected implicit def executor: ExecutionContext = system.dispatcher

  def shouldBeAdmin(): Unit =
    if (!currentUser.exists(_.isAdmin)) throw UserNotAuthorized("not authorized")

  before() {
    contentType = formats("json")
  }

  post("/", operation(fetchValpasDataForPersons)) {
    shouldBeAdmin()
    val personOids = parse(request.body).extract[Set[String]]
    val f: Future[Any] =
      valpasIntergration.fetch(ValpasQuery(personOids)).recoverWith { case t: Throwable =>
        Future.successful(InternalServerError(body = Map("reason" -> t.getMessage)))
      }

    new AsyncResult() {
      override implicit def timeout: Duration = 360.seconds

      override val is: Future[Any] = f
    }
  }

}
