package fi.vm.sade.hakurekisteri.web.integration.ytl

import _root_.akka.actor.{ActorRef, ActorSystem}
import _root_.akka.event.{Logging, LoggingAdapter}
import fi.vm.sade.hakurekisteri.integration.ytl.{Kokelas, Send, YtlIntegration}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.web.rest.support.{Security, SecuritySupport, UserNotAuthorized}
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.{Success, Try}

class YtlResource(ytl:ActorRef, ytlIntegration: YtlIntegration)(implicit val system: ActorSystem, val security: Security) extends HakuJaValintarekisteriStack with HakurekisteriJsonSupport with JacksonJsonSupport with SecuritySupport {


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
    logger.info("Fetching YTL data for everybody")
    ytlIntegration.syncAll()
    Accepted("YTL sync started")
  }
  post("/group_uuid_request/:group_uuid") {
    shouldBeAdmin
    val groupUuid = params("group_uuid")
    logger.info(s"Rewriting YTL data with group uuid $groupUuid")
    ytlIntegration.syncWithGroupUuid(groupUuid)
    Accepted(s"YTL sync with group uuid $groupUuid started")
  }
  get("/http_request/:personOid") {
    shouldBeAdmin
    val personOid = params("personOid")
    logger.info("Fetching YTL data for person OID")

    val done: Seq[Try[Kokelas]] = Await.result(ytlIntegration.sync(personOid), 10.seconds)
    val exists = done.exists{
      case Success(s) => true
      case _ => false
    }
    if(exists) {
      Accepted()
    } else {
      InternalServerError()
    }
  }

}
