package fi.vm.sade.hakurekisteri.web.restrictions

import _root_.akka.event.{Logging, LoggingAdapter}
import fi.vm.sade.hakurekisteri.integration.parametrit.{IsRestrictionActive, ParametritActorRef}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.web.rest.support.{QueryLogging, Security, SecuritySupport}
import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.{Swagger, SwaggerEngine}
import org.scalatra._

import scala.compat.Platform
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class RestrictionsResource(parameterActor: ParametritActorRef)
                         (implicit val sw: Swagger, system: ActorSystem, val security: Security)
  extends HakuJaValintarekisteriStack
  with FutureSupport with HakurekisteriJsonSupport with RestrictionsSwaggerApi with SecuritySupport with JacksonJsonSupport with QueryLogging{


  //override protected def applicationDescription: String = "Ohjausparametrirajoitteiden hakemisen rajapinta"
  override protected implicit def swagger: SwaggerEngine[_] = sw
  override protected implicit def executor: ExecutionContext = system.dispatcher
  override val logger: LoggingAdapter = Logging.getLogger(system, this)

  before() {
    contentType = formats("json")
  }

  get("/:restriction", operation(queryRestriction)) {
    val t0 = Platform.currentTime
    val restriction = params("restriction")

    new AsyncResult() {
      override implicit def timeout: Duration = 60.seconds
      private val q = (parameterActor.actor ? IsRestrictionActive (restriction))(60.seconds).mapTo[Boolean]
      logQuery(Map("restriction" -> restriction), t0, q)
      override val is = q
    }
  }

}
