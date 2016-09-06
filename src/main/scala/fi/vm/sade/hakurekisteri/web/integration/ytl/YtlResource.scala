package fi.vm.sade.hakurekisteri.web.integration.ytl

import _root_.akka.actor.{ActorRef, ActorSystem}
import _root_.akka.event.{Logging, LoggingAdapter}
import fi.vm.sade.hakurekisteri.integration.ytl.Send
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.web.rest.support.{Security, SecuritySupport, UserNotAuthorized}
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport

class YtlResource(ytl:ActorRef)(implicit val system: ActorSystem, val security: Security) extends HakuJaValintarekisteriStack with HakurekisteriJsonSupport with JacksonJsonSupport with SecuritySupport {


  override val logger: LoggingAdapter = Logging.getLogger(system, this)


  before() {
    contentType = formats("json")
  }

  def shouldBeAdmin = if (!currentUser.exists(_.isAdmin)) throw UserNotAuthorized("not authorized")

  get("/request") {
    shouldBeAdmin
    ytl ! Send
    Accepted()
  }
  get("/http_request") {
    shouldBeAdmin
    logger.info("Fetching YTL data for everybody")
    Accepted()
  }
  get("/http_request/:hetu") {
    shouldBeAdmin
    val hetu = params("hetu")
    logger.info("Fetching YTL data for henkilotunnus")
    Accepted()
  }

}
