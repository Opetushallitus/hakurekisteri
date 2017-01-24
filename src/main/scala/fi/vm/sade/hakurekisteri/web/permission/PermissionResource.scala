package fi.vm.sade.hakurekisteri.web.permission

import _root_.akka.actor.{ActorRef, ActorSystem}
import _root_.akka.event.{Logging, LoggingAdapter}
import _root_.akka.pattern.{AskTimeoutException, ask}
import _root_.akka.util.Timeout
import com.fasterxml.jackson.databind.JsonMappingException
import fi.vm.sade.hakurekisteri.integration.henkilo.PersonOidsWithAliases
import fi.vm.sade.hakurekisteri.opiskelija.{Opiskelija, OpiskelijaHenkilotQuery}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.hakurekisteri.suoritus._
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.web.rest.support.QueryLogging
import org.json4s.MappingException
import org.json4s.jackson.Serialization._
import org.scalatra._
import org.scalatra.json.JacksonJsonSupport
import org.scalatra.swagger.{Swagger, SwaggerEngine}

import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}


case class PermissionCheckRequest(personOidsForSamePerson: Set[String], organisationOids: Set[String]) {
  require(personOidsForSamePerson.nonEmpty, "Person oid list empty.")
  require(!personOidsForSamePerson.exists(_.isEmpty), "Blank person oid in oid list.")
  require(organisationOids.nonEmpty, "Organisation oid list empty.")
  require(!organisationOids.exists(_.isEmpty), "Blank organisation oid in organisation oid list.")
}

case class PermissionCheckResponse(accessAllowed: Option[Boolean] = None, errorMessage: Option[String] = None)

class PermissionResource(suoritusActor: ActorRef, opiskelijaActor: ActorRef, timeout: Option[Timeout] = Some(2.minutes))
                        (implicit system: ActorSystem, sw: Swagger) extends HakuJaValintarekisteriStack with PermissionSwaggerApi with HakurekisteriJsonSupport with JacksonJsonSupport with FutureSupport with QueryLogging {

  override protected def applicationDescription: String = "Oikeuksien tarkistuksen rajapinta"
  override protected implicit def swagger: SwaggerEngine[_] = sw
  override protected implicit def executor: ExecutionContext = system.dispatcher
  implicit val askTimeout: Timeout = timeout.getOrElse(2.minutes)
  override val logger: LoggingAdapter = Logging.getLogger(system, this)

  before() {
    contentType = formats("json")
  }
  
  private def grantsPermission(resources: Seq[_])(organisaatiot: Set[String]): Boolean = resources.exists {
    case s: VirallinenSuoritus => organisaatiot.contains(s.myontaja)
    case o: Opiskelija => organisaatiot.contains(o.oppilaitosOid)
    case _ => false
  }
  
  post("/", operation(checkPermission)) {
    val t0 = Platform.currentTime
    val r: PermissionCheckRequest = read[PermissionCheckRequest](request.body)

    new AsyncResult() {

      val permissionFuture = for {
        suoritukset: Seq[Suoritus] <- (suoritusActor ? SuoritusHenkilotQuery(PersonOidsWithAliases(r.personOidsForSamePerson))).mapTo[Seq[Suoritus]]
        opiskelijat: Seq[Opiskelija] <- (opiskelijaActor ? OpiskelijaHenkilotQuery(PersonOidsWithAliases(r.personOidsForSamePerson))).mapTo[Seq[Opiskelija]]
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
    case t: IllegalArgumentException =>
      logger.warning(s"cannot parse request object: $t")
      BadRequest(PermissionCheckResponse(errorMessage = Some(t.getMessage)))
    case MappingException(_, e: Throwable) if e.getCause != null => e.getCause match {
      case t: Throwable =>
        logger.warning(s"cannot parse request object: $t")
        BadRequest(PermissionCheckResponse(errorMessage = Some(t.getMessage)))
    }
    case t: JsonMappingException =>
      logger.warning(s"cannot parse request object: $t")
      BadRequest(PermissionCheckResponse(errorMessage = Some("cannot parse request object")))
    case t: AskTimeoutException =>
      logger.error(t, "permission check timed out")
      GatewayTimeout(PermissionCheckResponse(errorMessage = Some("timeout occurred during permission check")))
    case t: Throwable =>
      logger.error(t, "error occurred during permission check")
      InternalServerError(PermissionCheckResponse(errorMessage = Some(t.getMessage)))
  }

}
