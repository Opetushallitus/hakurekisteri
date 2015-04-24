package fi.vm.sade.hakurekisteri.web.integration.ytl

import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import org.scalatra.json.JacksonJsonSupport
import org.scalatra._
import fi.vm.sade.hakurekisteri.web.rest.support.{Security, SecuritySupport, UserNotAuthorized, SpringSecurity}
import _root_.akka.actor.{ActorSystem, ActorRef}
import scala.concurrent.ExecutionContext
import _root_.akka.event.{Logging, LoggingAdapter}
import fi.vm.sade.hakurekisteri.integration.ytl.Send

class YtlResource(ytl:ActorRef)(implicit val system: ActorSystem, val security: Security) extends HakuJaValintarekisteriStack with HakurekisteriJsonSupport with JacksonJsonSupport with CorsSupport with SecuritySupport {


  override val logger: LoggingAdapter = Logging.getLogger(system, this)


  before() {
    contentType = formats("json")
  }

  get("/request") {
    if (!currentUser.exists(_.isAdmin)) throw UserNotAuthorized("not authorized")
    else {
      ytl ! Send
      Accepted()
    }
  }

}
