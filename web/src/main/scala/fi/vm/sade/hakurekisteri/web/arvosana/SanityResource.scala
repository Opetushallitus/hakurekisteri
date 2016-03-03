package fi.vm.sade.hakurekisteri.web.arvosana

import akka.actor.{ActorRef, ActorSystem}
import akka.event.{Logging, LoggingAdapter}
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.arvosana.Problems
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.web.rest.support.{Security, SecuritySupport}
import org.scalatra.FutureSupport
import org.scalatra.json.JacksonJsonSupport

import scala.concurrent.ExecutionContext


class SanityResource(sanityActor: ActorRef)(implicit system: ActorSystem, val security: Security) extends HakuJaValintarekisteriStack  with HakurekisteriJsonSupport with JacksonJsonSupport with FutureSupport with SecuritySupport {

  override protected implicit def executor: ExecutionContext = system.dispatcher

  override val logger: LoggingAdapter = Logging.getLogger(system, this)

  import akka.pattern.ask

  import scala.concurrent.duration._

  before() {
    contentType = formats("json")
  }

  get("/perusopetus") {
    implicit val timeout: Timeout = 300.seconds
    sanityActor ? Problems

  }

}
