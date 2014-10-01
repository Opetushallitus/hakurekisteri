package fi.vm.sade.hakurekisteri.ensikertalainen

import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import fi.vm.sade.hakurekisteri.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.integration.PreconditionFailedException
import fi.vm.sade.hakurekisteri.rest.support.{SpringSecuritySupport, HakurekisteriJsonSupport}
import org.scalatra.swagger.{SwaggerEngine, Swagger}
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

case class Ensikertalainen(ensikertalainen: Boolean)
case class HetuNotFoundException(message: String) extends Exception(message)
case class ParamMissingException(message: String) extends IllegalArgumentException(message)

class EnsikertalainenResource(ensikertalainenActor: ActorRef)
                             (implicit val sw: Swagger, system: ActorSystem) extends HakuJaValintarekisteriStack with HakurekisteriJsonSupport with EnsikertalainenSwaggerApi with JacksonJsonSupport with FutureSupport with CorsSupport with SpringSecuritySupport {

  override protected def applicationDescription: String = "Korkeakouluhakujen kiintiöiden ensikertalaisuuden kyselyrajapinta"
  override protected implicit def swagger: SwaggerEngine[_] = sw
  override protected implicit def executor: ExecutionContext = system.dispatcher

  before() {
    contentType = formats("json")
  }

  options("/*") {
    response.setHeader("Access-Control-Allow-Headers", request.getHeader("Access-Control-Request-Headers"))
  }

  get("/", operation(query)) {
    try {
      val henkiloOid = params("henkilo")
      new AsyncResult() {
        override implicit def timeout: Duration = 90.seconds
        override val is = (ensikertalainenActor.?(EnsikertalainenQuery(henkiloOid))(90.seconds)).mapTo[Ensikertalainen]
      }
    } catch {
      case t: NoSuchElementException => throw ParamMissingException("parameter henkilo missing")
    }
  }

  incident {
    case t: ParamMissingException => (id) => BadRequest(IncidentReport(id, t.getMessage))
    case t: HetuNotFoundException => (id) => BadRequest(IncidentReport(id, "error validating hetu"))
    case t: PreconditionFailedException => (id) => InternalServerError(IncidentReport(id, "backend service failed"))
  }

}

