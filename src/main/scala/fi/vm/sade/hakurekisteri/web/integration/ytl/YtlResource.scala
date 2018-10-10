package fi.vm.sade.hakurekisteri.web.integration.ytl

import _root_.akka.actor.{ActorRef, ActorSystem}
import _root_.akka.event.{Logging, LoggingAdapter}
import fi.vm.sade.hakurekisteri.integration.ytl.{Kokelas, Send, YtlIntegration}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.web.rest.support.{Security, SecuritySupport, UserNotAuthorized}
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

class YtlResource(ytl: ActorRef, ytlIntegration: YtlIntegration)(implicit val system: ActorSystem, val security: Security) extends HakuJaValintarekisteriStack with HakurekisteriJsonSupport with JacksonJsonSupport with SecuritySupport {


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
  post("/http_request") {
    shouldBeAdmin
    val user = currentUser.get.username
    logger.info("Fetching YTL data for everybody")
    ytlIntegration.syncAll()
    Accepted("YTL sync started")
  }
  get("/http_request/:personOid") {
    shouldBeAdmin
    val personOid = params("personOid")
    logger.info(s"Fetching YTL data for person OID $personOid")

    val done: Seq[Try[Kokelas]] = Await.result(ytlIntegration.sync(personOid), 10.seconds)
    val exists = done.exists {
      case Success(s) => true
      case Failure(e) =>
        logger.error(e, s"Failure in syncing YTL data for person OID $personOid . Results: $done")
        false
    }
    if (exists) {
      Accepted()
    } else {
      logger.error(s"Failure in syncing YTL data for person OID $personOid . Returning error to caller. Got ${done.size} results: $done")
      InternalServerError()
    }
  }
}
