package fi.vm.sade.hakurekisteri.web.integration.ytl

import _root_.akka.actor.ActorSystem
import _root_.akka.event.{Logging, LoggingAdapter}
import fi.vm.sade.auditlog.{Changes, Target}
import fi.vm.sade.hakurekisteri.{AuditUtil, YTLSyncForAll, YTLSyncForPerson}
import fi.vm.sade.hakurekisteri.integration.ytl.{Kokelas, YtlIntegration}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.web.rest.support.{Security, SecuritySupport, UserNotAuthorized}
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.{Swagger, SwaggerEngine}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

class YtlResource(ytlIntegration: YtlIntegration)(implicit
  val system: ActorSystem,
  val security: Security,
  sw: Swagger
) extends HakuJaValintarekisteriStack
    with HakurekisteriJsonSupport
    with JacksonJsonSupport
    with SecuritySupport
    with YtlSwaggerApi {

  override val logger: LoggingAdapter = Logging.getLogger(system, this)
  override protected implicit def swagger: SwaggerEngine[_] = sw
  override protected def applicationDescription: String = "Ytl-Resource"

  before() {
    contentType = formats("json")
  }

  def shouldBeAdmin = if (!currentUser.exists(_.isAdmin)) throw UserNotAuthorized("not authorized")

  get("/request") {
    shouldBeAdmin
    Accepted()
  }
  post("/http_request") {
    shouldBeAdmin
    logger.info("Fetching YTL data for everybody")
    audit.log(auditUser, YTLSyncForAll, new Target.Builder().build, Changes.EMPTY)
    ytlIntegration.syncAll()
    Accepted("YTL sync started")
  }
  post("/http_request_byhaku") {
    shouldBeAdmin
    logger.info("Fetching YTL data for everybody")
    audit.log(auditUser, YTLSyncForAll, new Target.Builder().build, Changes.EMPTY)
    val tunniste = ytlIntegration.syncAllOneHakuAtATime()
    Accepted(s"YTL sync started, tunniste $tunniste")
  }
  get("/http_request_byhaku/:hakuOid") {
    shouldBeAdmin
    val hakuOid = params("hakuOid")
    logger.info(s"Syncing YTL data for haku $hakuOid")
    audit.log(auditUser, YTLSyncForAll, new Target.Builder().build, Changes.EMPTY)
    val tunniste = ytlIntegration.syncOneHaku(hakuOid)
    Accepted(s"YTL sync started for haku $hakuOid, tunniste $tunniste")
  }
  get("/http_request/:personOid", operation(syncPerson)) {
    shouldBeAdmin
    val personOid = params("personOid")
    logger.info(s"Fetching YTL data for person OID $personOid")
    audit.log(auditUser, YTLSyncForPerson, AuditUtil.targetFromParams(params).build, Changes.EMPTY)
    try {
      val done: Try[Kokelas] = Await.result(ytlIntegration.syncSingle(personOid), 30.seconds)
      val success = done match {
        case Success(s) => true
        case Failure(e) =>
          logger.error(e, s"Failure in syncing YTL data for person OID $personOid . Results: $done")
          false
      }
      if (success) {
        Accepted()
      } else {
        val message =
          s"Failure in syncing YTL data for single person $personOid."
        logger.error(message)
        BadRequest(message)
      }
    } catch {
      case t: Throwable =>
        val errorStr = s"Failure in syncing YTL data for single person $personOid"
        logger.error(errorStr, t)
        InternalServerError(errorStr)
    }
  }
}
