package fi.vm.sade.hakurekisteri.web.koski

import akka.actor.ActorSystem
import akka.event.{Logging, LoggingAdapter}
import org.scalatra.json.JacksonJsonSupport
import fi.vm.sade.auditlog.{Audit, Changes, Target}
import fi.vm.sade.hakurekisteri._
import fi.vm.sade.hakurekisteri.integration.koski.{IKoskiService, KoskiService, KoskiSuoritusHakuParams}
import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriJsonSupport, User}
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.web.rest.support.{Security, SecuritySupport, UserNotAuthorized}
import org.scalatra.{AsyncResult, FutureSupport}
import org.scalatra.swagger.{Swagger, SwaggerEngine}

import scala.compat.Platform
import scala.concurrent.{ExecutionContext, Future}

class KoskiImporterResource(koskiService: IKoskiService, ophConfig: Config)
                           (implicit val system: ActorSystem, sw: Swagger, val security: Security)
  extends HakuJaValintarekisteriStack
    with KoskiImporterSwaggerApi
    with HakurekisteriJsonSupport
    with FutureSupport
    with SecuritySupport
    with JacksonJsonSupport {

  override protected implicit def executor: ExecutionContext = system.dispatcher

  override val logger: LoggingAdapter = Logging.getLogger(system, this)

  override protected implicit def swagger: SwaggerEngine[_] = sw

  override protected def applicationDescription: String = "Koski integraation rest-api"

  def getAdmin: User = {
    currentUser match {
      case Some(u) if u.isAdmin => u
      case None => throw UserNotAuthorized(s"anonymous access not allowed")
    }
  }

  get("/:oppijaOid", operation(read)) {

    implicit val user: User = getAdmin
    val personOid = params("oppijaOid")
    val haeLukio: Boolean = params.getAsOrElse("haelukio", false)
    val haeAmmatilliset: Boolean = params.getAsOrElse("haeammatilliset", false)

    audit.log(auditUser,
      OppijanTietojenPaivitysKoskesta,
      new Target.Builder()
        .setField("oppijaOid", personOid)
        .setField("haeLukio", haeLukio.toString)
        .setField("haeAmmatilliset", haeAmmatilliset.toString).build(),
      Changes.EMPTY)
    new AsyncResult {
      override val is: Future[_] = koskiService.updateHenkilot(Set(personOid), KoskiSuoritusHakuParams(saveLukio = haeLukio, saveAmmatillinen = haeAmmatilliset))
    }
  }

  post("/oppijat", operation(updateHenkilot)) {
    implicit val user: User = getAdmin
    val personOids = parse(request.body).extract[Set[String]]
    val haeLukio: Boolean = params.getAsOrElse("haelukio", false)
    val haeAmmatilliset: Boolean = params.getAsOrElse("haeammatilliset", false)
    val maxOppijatPostSize: Int = ophConfig.integrations.koskiMaxOppijatPostSize

    if (personOids.size > maxOppijatPostSize) {
      val msg = s"too many person oids: ${personOids.size} was greater than the allowed maximum ${maxOppijatPostSize}"
      throw new IllegalArgumentException(msg)
    }
    audit.log(auditUser,
      OppijoidenTietojenPaivitysKoskesta,
      AuditUtil.targetFromParams(params)
        .setField("oppijaOids", personOids.toString()).build(),
      Changes.EMPTY)
    new AsyncResult {
      override val is: Future[_] = koskiService.updateHenkilot(personOids, KoskiSuoritusHakuParams(saveLukio = haeLukio, saveAmmatillinen = haeAmmatilliset))
    }
  }

  get("/haku/:hakuOid", operation(updateForHaku)) {
    implicit val user: User = getAdmin
    val hakuOid = params("hakuOid")
    val haeLukio: Boolean = params.getAsOrElse("haelukio", false)
    val haeAmmatilliset: Boolean = params.getAsOrElse("haeammatilliset", false)
    val useBulk: Boolean = params.getAsOrElse("bulk", false)
    audit.log(auditUser,
      HaunHakijoidenTietojenPaivitysKoskesta,
      new Target.Builder()
        .setField("hakuOid", hakuOid)
        .setField("haeLukio", haeLukio.toString)
        .setField("haeAmmatilliset", haeAmmatilliset.toString).build(),
      Changes.EMPTY)
    new AsyncResult {
      override val is: Future[_] = koskiService.updateHenkilotForHaku(hakuOid, KoskiSuoritusHakuParams(saveLukio = haeLukio, saveAmmatillinen = haeAmmatilliset))
    }
  }

}
