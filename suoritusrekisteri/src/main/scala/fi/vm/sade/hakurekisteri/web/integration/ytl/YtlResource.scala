package fi.vm.sade.hakurekisteri.web.integration.ytl

import _root_.akka.actor.ActorSystem
import _root_.akka.event.{Logging, LoggingAdapter}
import akka.pattern.ask
import akka.util.Timeout
import fi.vm.sade.auditlog.{Changes, Target}
import fi.vm.sade.hakurekisteri.{
  AuditUtil,
  YTLSyncForAll,
  YTLSyncForHaku,
  YTLSyncForPerson,
  YTLSyncForPersons
}
import fi.vm.sade.hakurekisteri.integration.ytl.{
  Kokelas,
  YtlFetchActorRef,
  YtlSyncAllHaut,
  YtlSyncHaku,
  YtlSyncSingle
}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.web.rest.support.{Security, SecuritySupport, UserNotAuthorized}
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.{Swagger, SwaggerEngine}

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

class YtlResource(ytlFetchActor: YtlFetchActorRef)(implicit
  val system: ActorSystem,
  val security: Security,
  sw: Swagger
) extends HakuJaValintarekisteriStack
    with HakurekisteriJsonSupport
    with JacksonJsonSupport
    with SecuritySupport
    with YtlSwaggerApi
    with FutureSupport {

  override val logger: LoggingAdapter = Logging.getLogger(system, this)
  override protected implicit def swagger: SwaggerEngine[_] = sw
  override protected def applicationDescription: String = "Ytl-Resource"
  override protected implicit def executor: ExecutionContext = system.dispatcher

  before() {
    contentType = formats("json")
  }

  def shouldBeAdmin = if (!currentUser.exists(_.isAdmin)) throw UserNotAuthorized("not authorized")

  post("/http_request", operation(syncAll)) {
    shouldBeAdmin
    logger.info("Fetching YTL data for everybody")
    audit.log(auditUser, YTLSyncForAll, new Target.Builder().build, Changes.EMPTY)
    val tunniste = "manual_sync_for_all_hakus" + System.currentTimeMillis()
    ytlFetchActor.actor ! YtlSyncAllHaut(tunniste)
    Accepted(s"YTL sync started, tunniste $tunniste")
  }

  get("/http_request_byhaku/:hakuOid", operation(syncHaku)) {
    shouldBeAdmin
    val hakuOid = params("hakuOid")
    logger.info(s"Syncing YTL data for haku $hakuOid")
    audit.log(auditUser, YTLSyncForHaku, AuditUtil.targetFromParams(params).build, Changes.EMPTY)
    val tunniste = "manual_sync_for_haku_" + hakuOid
    ytlFetchActor.actor ! YtlSyncHaku(hakuOid, tunniste)
    logger.info(s"Returning tunniste $tunniste to caller")
    Accepted(s"YTL sync started for haku $hakuOid, tunniste $tunniste")
  }

  get("/http_request/:personOid", operation(syncPerson)) {
    implicit val to: Timeout = Timeout(30.seconds)
    shouldBeAdmin
    val personOid = params("personOid")
    logger.info(s"Fetching YTL data for person OID $personOid")
    audit.log(auditUser, YTLSyncForPerson, AuditUtil.targetFromParams(params).build, Changes.EMPTY)
    try {
      val resultF = ytlFetchActor.actor ? YtlSyncSingle(
        personOid,
        tunniste = s"manual_sync_for_person_${personOid}"
      ) recoverWith { case t: Throwable =>
        logger.error(t, s"Error while ytl-syncing $personOid")
        Future.failed(
          new RuntimeException(s"Error while ytl-syncing $personOid: ${t.getMessage}")
        )
      }
      logger.info(s"Waiting for result for YTL data for person OID $personOid")
      val result = Await.result(resultF, 30.seconds)
      logger.info(s"Got result for YTL data for person OID $personOid: $result")
      Accepted(result)
    } catch {
      case t: Throwable =>
        val errorStr = s"Failure in syncing YTL data for single person $personOid"
        logger.error(t, errorStr)
        InternalServerError(errorStr)
    }
  }

  post("/http_request/persons", operation(syncPersons)) {
    implicit val to: Timeout = Timeout(30.seconds)
    shouldBeAdmin
    val personOids = parse(request.body).extract[Set[String]]
    logger.info(s"Fetching YTL data for multiple person OIDs $personOids")
    audit.log(
      auditUser,
      YTLSyncForPersons,
      AuditUtil.targetFromParams(params).setField("oppijaOids", personOids.toString()).build(),
      Changes.EMPTY
    )
    personOids.foreach(personOid => {
      ytlFetchActor.actor ? YtlSyncSingle(
        personOid,
        tunniste = s"manual_sync_for_person_${personOid}"
      )
    })
    Accepted(s"YTL sync started for persons $personOids")
  }
}
