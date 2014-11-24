package fi.vm.sade.hakurekisteri.integration.virta

import _root_.akka.actor.{ActorSystem, ActorRef}
import _root_.akka.event.{Logging, LoggingAdapter}
import _root_.akka.pattern.AskTimeoutException
import fi.vm.sade.hakurekisteri.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.healthcheck.Status
import fi.vm.sade.hakurekisteri.rest.support.{IncidentReport, UserNotAuthorized, SpringSecuritySupport, HakurekisteriJsonSupport}
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport

import scala.concurrent.ExecutionContext

class VirtaResource(virtaQueue: ActorRef) (implicit system: ActorSystem) extends HakuJaValintarekisteriStack with HakurekisteriJsonSupport with JacksonJsonSupport with FutureSupport with CorsSupport with SpringSecuritySupport {

  override protected implicit def executor: ExecutionContext = system.dispatcher

  override val logger: LoggingAdapter = Logging.getLogger(system, this)

  import scala.concurrent.duration._
  import _root_.akka.pattern.ask

  before() {
    contentType = formats("json")
  }

  get("/sync") {
    if (!currentUser.exists(_.orgsFor("WRITE", "Virta").contains("1.2.246.562.10.00000000001"))) throw UserNotAuthorized("not authorized")
    else new AsyncResult() {
      override implicit def timeout: Duration = 120.seconds

      virtaQueue ! ConsumeAll

      override val is = (virtaQueue ? VirtaHealth)(120.seconds).mapTo[VirtaStatus].recover {
        case e: AskTimeoutException => VirtaStatus(None, 0, Status.TIMEOUT)
        case e: Throwable => VirtaStatus(None, 0, Status.FAILURE)
      }
    }
  }

  incident {
    case t: UserNotAuthorized => (id) => Forbidden(IncidentReport(id, t.message))
  }
}
