package fi.vm.sade.hakurekisteri.web.oppija


import akka.event.{LoggingAdapter, Logging}
import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.integration.hakemus.{FullHakemus, HakemusQuery, HenkiloHakijaQuery}
import fi.vm.sade.hakurekisteri.integration.virta.VirtaConnectionErrorException
import fi.vm.sade.hakurekisteri.rest.support._
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.{SwaggerEngine, Swagger}

import scala.compat.Platform
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.web.rest.support.{UserNotAuthorized, QueryLogging, IncidentReport}
import fi.vm.sade.hakurekisteri.oppija.OppijaFetcher


class OppijaResource(val rekisterit: Registers, val hakemusRekisteri: ActorRef, val ensikertalaisuus: ActorRef)
                    (implicit val system: ActorSystem, sw: Swagger)
    extends HakuJaValintarekisteriStack with OppijaFetcher with OppijaSwaggerApi with HakurekisteriJsonSupport with JacksonJsonSupport with FutureSupport with CorsSupport with SpringSecuritySupport with QueryLogging {

  override protected def applicationDescription: String = "Oppijan tietojen koosterajapinta"
  override protected implicit def swagger: SwaggerEngine[_] = sw
  override protected implicit def executor: ExecutionContext = system.dispatcher
  implicit val defaultTimeout: Timeout = 500.seconds
  override val logger: LoggingAdapter = Logging.getLogger(system, this)

  options("/*") {
    response.setHeader("Access-Control-Allow-Headers", request.getHeader("Access-Control-Request-Headers"))
  }

  before() {
    contentType = formats("json")
  }

  def getUser: User = {
    currentUser match {
      case Some(u) => u
      case None => throw UserNotAuthorized(s"anonymous access not allowed")
    }
  }

  get("/", operation(query)) {
    val t0 = Platform.currentTime
    implicit val user = getUser
    val q = HakemusQuery(params)

    new AsyncResult() {
      override implicit def timeout: Duration = 500.seconds

      private val oppijatFuture = fetchOppijat(q)

      logQuery(q, t0, oppijatFuture)

      val is = oppijatFuture
    }
  }

  get("/:oid", operation(read)) {
    val t0 = Platform.currentTime
    implicit val user = getUser
    val q = HenkiloHakijaQuery(params("oid"))

    new AsyncResult() {
      override implicit def timeout: Duration = 500.seconds

      private val oppijaFuture = for (
        hakemukset <- (hakemusRekisteri ? q).mapTo[Seq[FullHakemus]];
        oppijat <- fetchOppijatFor(hakemukset.filter((fh) => fh.personOid.isDefined && fh.hetu.isDefined).slice(0, 1))
      ) yield {
        oppijat.headOption.fold(NotFound(body = ""))(Ok(_))
      }

      logQuery(q, t0, oppijaFuture)

      val is = oppijaFuture
    }

  }

  incident {
    case t: VirtaConnectionErrorException => (id) => InternalServerError(IncidentReport(id, "virta error"))
  }
}

