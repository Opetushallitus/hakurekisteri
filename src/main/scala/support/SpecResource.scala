package support

import java.util.UUID

import _root_.akka.actor.{ActorRef, ActorSystem}
import _root_.akka.event.{Logging, LoggingAdapter}
import fi.vm.sade.hakurekisteri.integration.ytl.YtlResult
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.web.rest.support.{Security, SecuritySupport}
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport

class SpecResource(ytlActor: ActorRef)(implicit val system: ActorSystem, val security: Security) extends HakuJaValintarekisteriStack with HakurekisteriJsonSupport with JacksonJsonSupport with SecuritySupport {

  override val logger: LoggingAdapter = Logging.getLogger(system, this)

  before() {
    contentType = formats("json")
  }

  get("/ytl/process/:filename") {
    ytlActor ! YtlResult(UUID.randomUUID(), getClass.getResource("/" + params("filename")).getFile)
    Accepted()
  }

}
