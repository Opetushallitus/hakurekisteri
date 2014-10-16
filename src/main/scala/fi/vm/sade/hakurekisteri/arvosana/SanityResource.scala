package fi.vm.sade.hakurekisteri.arvosana

import akka.actor.{ActorSystem, ActorRef}
import akka.event.{Logging, LoggingAdapter}
import fi.vm.sade.hakurekisteri.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.rest.support.{SpringSecuritySupport, HakurekisteriJsonSupport}
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.{CorsSupport, FutureSupport}
import scala.concurrent.ExecutionContext
import akka.util.Timeout


class SanityResource(sanityActor: ActorRef)(implicit system: ActorSystem) extends HakuJaValintarekisteriStack  with HakurekisteriJsonSupport with JacksonJsonSupport with FutureSupport with CorsSupport with SpringSecuritySupport{

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
