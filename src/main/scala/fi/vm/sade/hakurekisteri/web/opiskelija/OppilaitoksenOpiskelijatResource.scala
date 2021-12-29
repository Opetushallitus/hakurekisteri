package fi.vm.sade.hakurekisteri.web.opiskelija

import akka.actor.{ActorRef, ActorSystem}
import akka.event.{Logging, LoggingAdapter}
import akka.util.Timeout
import fi.vm.sade.auditlog.{Changes, Target}
import fi.vm.sade.hakurekisteri.{AuditUtil, ResourceRead}
import fi.vm.sade.hakurekisteri.integration.hakemus.{HakemusQuery, IHakemusService}
import fi.vm.sade.hakurekisteri.integration.haku.HakuNotFoundException
import fi.vm.sade.hakurekisteri.integration.henkilo.IOppijaNumeroRekisteri
import fi.vm.sade.hakurekisteri.oppija.OppijaFetcher
import fi.vm.sade.hakurekisteri.rest.support._
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.web.rest.support._
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.{Swagger, SwaggerEngine}
import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class OppilaitoksenOpiskelijatResource(
                                      val rekisterit: Registers,
                                      val hakemusService: IHakemusService
                                      )(implicit val system: ActorSystem, sw: Swagger, val security: Security)
  extends HakuJaValintarekisteriStack
    with OppijaFetcher
    with OppilaitoksenOpiskelijaSwaggerApi
    with HakurekisteriJsonSupport
    with JacksonJsonSupport
    with FutureSupport
    with SecuritySupport
    with QueryLogging {

  override protected implicit def swagger: SwaggerEngine[_] = sw
  override protected implicit def executor: ExecutionContext = system.dispatcher
  implicit val defaultTimeout: Timeout = 500.seconds
  override val logger: LoggingAdapter = Logging.getLogger(system, this)

  before() {
    contentType = formats("json")
  }

  def getUser: User = {
    currentUser match {
      case Some(u) => u
      case None    => throw UserNotAuthorized(s"anonymous access not allowed")
    }
  }

  get("/:oppilaitosOid", operation(query)) {
    import org.scalatra.util.RicherString._
    val t0 = Platform.currentTime
    implicit val user = getUser
    val ensikertalaisuudet = params.getOrElse("ensikertalaisuudet", "true").toBoolean
    val q = HakemusQuery(
      haku = if (ensikertalaisuudet) Some(params("haku")) else params.get("haku"),
      organisaatio = params.get("organisaatio").flatMap(_.blankOption),
      None,
      hakukohde = params.get("hakukohde").flatMap(_.blankOption)
    )

    audit.log(
      auditUser,
      ResourceRead,
      AuditUtil
        .targetFromParams(params)
        .setField("resource", "OppilaitoksenOpiskelijatResource")
        .setField("summary", query.result.summary)
        .build(),
      Changes.EMPTY
    )

    new AsyncResult() {
      override implicit def timeout: Duration = 500.seconds

      private val oppijatFuture = fetchOppijat(ensikertalaisuudet, q)

      logQuery(q, t0, oppijatFuture)

      val is = oppijatFuture
    }
  }

  incident {
    case t: HakuNotFoundException    => (id) => NotFound(IncidentReport(id, t.getMessage))
    case t: NoSuchElementException   => (id) => BadRequest(IncidentReport(id, t.getMessage))
    case t: IllegalArgumentException => (id) => BadRequest(IncidentReport(id, t.getMessage))
  }
}
