package fi.vm.sade.hakurekisteri.web.oppilaitos

import akka.actor.{ActorRef, ActorSystem}
import akka.event.{Logging, LoggingAdapter}
import akka.pattern.ask
import akka.util.Timeout
import fi.vm.sade.auditlog.Changes
import fi.vm.sade.hakurekisteri.opiskelija.{
  Opiskelija,
  OppilaitoksenOpiskelija,
  OppilaitoksenOpiskelijatQuery
}
import fi.vm.sade.hakurekisteri.organization.AuthorizedQuery
import fi.vm.sade.hakurekisteri.rest.support._
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.web.rest.support._
import fi.vm.sade.hakurekisteri.{AuditUtil, ResourceRead}
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.{Swagger, SwaggerEngine}

import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class OppilaitosResource(opiskelijaActor: ActorRef)(implicit
  sw: Swagger,
  val security: Security,
  val system: ActorSystem
) extends HakuJaValintarekisteriStack
    with OppilaitoksenOpiskelijaSwaggerApi
    with HakurekisteriJsonSupport
    with JacksonJsonSupport
    with FutureSupport
    with SecuritySupport
    with QueryLogging {

  override protected def applicationDescription: String = "Oppilaitoksen opiskelijat rajapinta"
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

  get("/:oppilaitosOid/opiskelijat", operation(query)) {
    val t0 = Platform.currentTime
    implicit val user: User = getUser

    val q = OppilaitoksenOpiskelijatQuery.apply(params)

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

      private val oppilaitoksenOpiskelijatFuture = fetchOppilaitoksenOpiskelijat(q)

      logQuery(q, t0, oppilaitoksenOpiskelijatFuture)

      override val is = oppilaitoksenOpiskelijatFuture
    }
  }

  get("/:oppilaitosOid/luokat", operation(query)) {
    val t0 = Platform.currentTime
    implicit val user: User = getUser

    val q = OppilaitoksenOpiskelijatQuery.apply(params)

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

      private val oppilaitoksenLuokatFuture = fetchOppilaitoksenLuokat(q)

      logQuery(q, t0, oppilaitoksenLuokatFuture)

      override val is = oppilaitoksenLuokatFuture
    }
  }

  private def fetchOppilaitoksenLuokat(
    q: OppilaitoksenOpiskelijatQuery
  )(implicit user: User): Future[Seq[String]] = {
    (opiskelijaActor ? AuthorizedQuery(q, user)).map(opiskelijat => {
      opiskelijat
        .asInstanceOf[Seq[Opiskelija]]
        .map(oppilas => oppilas.luokka)
        .distinct
    })
  }

  private def fetchOppilaitoksenOpiskelijat(
    q: OppilaitoksenOpiskelijatQuery
  )(implicit user: User): Future[Seq[OppilaitoksenOpiskelija]] = {
    (opiskelijaActor ? AuthorizedQuery(q, user)).map(opiskelijat => {
      opiskelijat
        .asInstanceOf[Seq[Opiskelija]]
        .map(oppilas => OppilaitoksenOpiskelija(oppilas.henkiloOid, oppilas.luokka))
    })
  }

  incident {
    case t: NoSuchElementException   => (id) => BadRequest(IncidentReport(id, t.getMessage))
    case t: IllegalArgumentException => (id) => BadRequest(IncidentReport(id, t.getMessage))
  }

}
