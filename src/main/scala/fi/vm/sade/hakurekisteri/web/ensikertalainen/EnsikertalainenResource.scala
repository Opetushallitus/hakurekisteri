package fi.vm.sade.hakurekisteri.web.ensikertalainen

import java.util.concurrent.ExecutionException

import akka.actor.{ActorRef, ActorSystem}
import akka.event.{Logging, LoggingAdapter}
import akka.pattern.ask
import fi.vm.sade.auditlog.{Changes, Target}
import fi.vm.sade.hakurekisteri.{AuditUtil, EnsikertalainenHaussaQuery, KaikkiHaunEnsikertalaiset}
import fi.vm.sade.hakurekisteri.ensikertalainen.{Ensikertalainen, EnsikertalainenQuery, HaunEnsikertalaisetQuery}
import fi.vm.sade.hakurekisteri.integration.PreconditionFailedException
import fi.vm.sade.hakurekisteri.integration.hakemus.{HakemusService, IHakemusService}
import fi.vm.sade.hakurekisteri.integration.haku.HakuNotFoundException
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.web.rest.support.{IncidentReport, QueryLogging, Security, SecuritySupport}
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.{Swagger, SwaggerEngine}

import scala.compat.Platform
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

case class ParamMissingException(message: String) extends IllegalArgumentException(message)

class EnsikertalainenResource(ensikertalainenActor: ActorRef, val hakemusService: IHakemusService)
                             (implicit val sw: Swagger, system: ActorSystem, val security: Security)
  extends HakuJaValintarekisteriStack with HakurekisteriJsonSupport with EnsikertalainenSwaggerApi with JacksonJsonSupport
    with FutureSupport with SecuritySupport with QueryLogging {

  override protected def applicationDescription: String = "Korkeakouluhakujen kiintiöiden ensikertalaisuuden kyselyrajapinta"
  override protected implicit def swagger: SwaggerEngine[_] = sw
  override protected implicit def executor: ExecutionContext = system.dispatcher
  override val logger: LoggingAdapter = Logging.getLogger(system, this)

  before() {
    contentType = formats("json")
  }

  get("/", operation(query)) {
    val t0 = Platform.currentTime
    val henkiloOid = params("henkilo")
    val hakuOid = params("haku")

    audit.log(auditUser,
      EnsikertalainenHaussaQuery,
      new Target.Builder().setField("henkilo", henkiloOid).setField("haku", hakuOid).build(),
      new Changes.Builder().build())
    new AsyncResult() {
      override implicit def timeout: Duration = 60.seconds
      private val q = (ensikertalainenActor ? EnsikertalainenQuery(
        henkiloOids = Set(henkiloOid),
        hakuOid = hakuOid
      ))(60.seconds).mapTo[Seq[Ensikertalainen]].map(_.head)
      logQuery(Map("henkilo" -> henkiloOid, "haku" -> hakuOid), t0, q)
      override val is = q
    }
  }

  get("/haku/:haku", operation(hakuQuery)) {
    val t0 = Platform.currentTime
    val hakuOid = params("haku")
    audit.log(auditUser,
      KaikkiHaunEnsikertalaiset,
      AuditUtil.targetFromParams(params).build(),
      new Changes.Builder().build())
    new AsyncResult() {
      override implicit def timeout: Duration = 15.minutes
      override val is = (ensikertalainenActor ? HaunEnsikertalaisetQuery(hakuOid))(15.minutes).mapTo[Seq[Ensikertalainen]]
      logQuery(Map("haku" -> hakuOid), t0, is)
    }
  }

  post("/", operation(postQuery)) {
    val t0 = Platform.currentTime
    val personOids = parse(request.body).extract[Set[String]]
    if (personOids.isEmpty) throw ParamMissingException("request body does not contain person oids")
    val hakuOid = params("haku")

    audit.log(auditUser,
      EnsikertalainenHaussaQuery,
      new Target.Builder().setField("henkilot", personOids.toString()).setField("haku", hakuOid).build(),
      new Changes.Builder().build())

    new AsyncResult() {
      override implicit def timeout: Duration = 5.minutes
      private val q = (ensikertalainenActor ? EnsikertalainenQuery(
        henkiloOids = personOids,
        hakuOid = hakuOid
      ))(5.minutes).mapTo[Seq[Ensikertalainen]]
      logQuery(Map("body" -> personOids, "haku" -> hakuOid), t0, q)
      override val is = q
    }
  }

  incident {
    case t: HakuNotFoundException => (id) => NotFound(IncidentReport(id, t.getMessage))
    case t: NoSuchElementException => (id) => BadRequest(IncidentReport(id, t.getMessage))
    case t: ParamMissingException => (id) => BadRequest(IncidentReport(id, t.getMessage))
    case t: ExecutionException => (id) => InternalServerError(IncidentReport(id, "backend service failed"))
    case t: PreconditionFailedException => (id) => InternalServerError(IncidentReport(id, "backend service failed"))
  }

}

