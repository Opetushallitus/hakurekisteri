package fi.vm.sade.hakurekisteri.web.permission

import _root_.akka.actor.{ActorRef, ActorSystem}
import _root_.akka.pattern.{AskTimeoutException, ask}
import _root_.akka.event.{Logging, LoggingAdapter}
import _root_.akka.util.Timeout
import com.fasterxml.jackson.databind.JsonMappingException
import fi.vm.sade.hakurekisteri.opiskelija.{OpiskelijaHenkilotQuery, Opiskelija}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.hakurekisteri.suoritus._
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.web.rest.support.QueryLogging
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport
import org.json4s.jackson.Serialization._


import scala.compat.Platform
import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.duration._


case class PermissionCheckRequest(personOidsForSamePerson: Set[String], organisationOids: Set[String])

case class PermissionCheckResponse(accessAllowed: Option[Boolean] = None, errorMessage: Option[String] = None)

class PermissionResource(suoritusActor: ActorRef, opiskelijaActor: ActorRef, timeout: Option[Timeout] = Some(2.minutes))(implicit system: ActorSystem) extends HakuJaValintarekisteriStack with HakurekisteriJsonSupport with JacksonJsonSupport with FutureSupport with CorsSupport with QueryLogging {

  override protected implicit def executor: ExecutionContext = system.dispatcher
  override val logger: LoggingAdapter = Logging.getLogger(system, this)
  implicit val askTimeout: Timeout = timeout.getOrElse(2.minutes)

  options("/*") {
    response.setHeader("Access-Control-Allow-Headers", request.getHeader("Access-Control-Request-Headers"))
  }

  before() {
    contentType = formats("json")
  }
  
  private def grantsPermission(resources: Seq[_])(organisaatiot: Set[String]): Boolean = resources.exists {
    case s: VirallinenSuoritus => organisaatiot.contains(s.myontaja)
    case o: Opiskelija => organisaatiot.contains(o.oppilaitosOid)
    case _ => false
  }
  
  post("/") {
    val t0 = Platform.currentTime
    val r: PermissionCheckRequest = read[PermissionCheckRequest](request.body)

    new AsyncResult() {

      val permissionFuture = for {
        suoritukset: Seq[Suoritus] <- (suoritusActor ? SuoritusHenkilotQuery(r.personOidsForSamePerson)).mapTo[Seq[Suoritus]]
        opiskelijat: Seq[Opiskelija] <- (opiskelijaActor ? OpiskelijaHenkilotQuery(r.personOidsForSamePerson)).mapTo[Seq[Opiskelija]]
      } yield
        PermissionCheckResponse(
          accessAllowed = Some(
            grantsPermission(suoritukset ++ opiskelijat)(r.organisationOids)
          )
        )

      logQuery(r, t0, permissionFuture)

      override val is: Future[PermissionCheckResponse] = permissionFuture

      override implicit def timeout: Duration = 2.minutes

    }
  }
  
  error {
    case t: AskTimeoutException =>
      logger.error(t, "permission check timed out")
      GatewayTimeout(PermissionCheckResponse(errorMessage = Some("timeout occurred during permission check")))
    case t: JsonMappingException =>
      logger.warning(s"cannot parse request object: $t")
      BadRequest(PermissionCheckResponse(errorMessage = Some("cannot parse request object")))
    case t: Throwable =>
      logger.error(t, "error occurred during permission check")
      InternalServerError(PermissionCheckResponse(errorMessage = Some(t.getMessage)))
  }

}
