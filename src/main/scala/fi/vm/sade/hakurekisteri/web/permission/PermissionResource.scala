package fi.vm.sade.hakurekisteri.web.permission

import _root_.akka.actor.{ActorRef, ActorSystem}
import _root_.akka.event.{Logging, LoggingAdapter}
import _root_.akka.pattern.{AskTimeoutException, ask}
import _root_.akka.util.Timeout
import com.fasterxml.jackson.databind.JsonMappingException
import fi.vm.sade.hakurekisteri.integration.hakemus.HasPermissionForOrgs
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
import scala.concurrent.{Await, ExecutionContext, Future}

class PermissionResource(suoritusActor: ActorRef,
                         opiskelijaActor: ActorRef,
                         hakemusBasedPermissionCheckerActor: ActorRef,
                         timeout: Option[Timeout] = Some(2.minutes)
                        )
                        (implicit system: ActorSystem, sw: Swagger)
  extends HakuJaValintarekisteriStack with PermissionSwaggerApi with HakurekisteriJsonSupport with JacksonJsonSupport with FutureSupport with QueryLogging {

  override protected def applicationDescription: String = "Oikeuksien tarkistuksen rajapinta"
  override protected implicit def swagger: SwaggerEngine[_] = sw
  override protected implicit def executor: ExecutionContext = system.dispatcher
  implicit val askTimeout: Timeout = timeout.getOrElse(2.minutes)
  override val logger: LoggingAdapter = Logging.getLogger(system, this)

  before() {
    contentType = formats("json")
  }
  
  post("/", operation(checkPermission)) {
    val t0 = Platform.currentTime
    val r: PermissionCheckRequest = read[PermissionCheckRequest](request.body)
    logger.info(s"Checking permission for: personOidsForSamePerson ${r.personOidsForSamePerson} organisationOids ${r.organisationOids}.")

    new AsyncResult() {
      val permissionFuture = for {
        suoritukset: Seq[Suoritus] <- (suoritusActor ? SuoritusHenkilotQuery(PersonOidsWithAliases(r.personOidsForSamePerson))).mapTo[Seq[Suoritus]]
        opiskelijat: Seq[Opiskelija] <- (opiskelijaActor ? OpiskelijaHenkilotQuery(PersonOidsWithAliases(r.personOidsForSamePerson))).mapTo[Seq[Opiskelija]]
      } yield {
        val organisationGrantsPermission = grantsPermission(suoritukset ++ opiskelijat, r.organisationOids)
        logger.info(s"Finished querying for permissions. organisationGrantsPermission: ${organisationGrantsPermission}")
        val result: Boolean = organisationGrantsPermission /*|| {
          logger.info("Checking if permission can be granted based on hakemus")
          Await.result(hakemusGrantsPermission(r.personOidsForSamePerson, r.organisationOids), 10.seconds)
        }*/
        PermissionCheckResponse(
          accessAllowed = Some(result)
        )
      }

      logQuery(r, t0, permissionFuture)

      override val is: Future[PermissionCheckResponse] = permissionFuture
      override implicit def timeout: Duration = 2.minutes
    }
  }

  def hakemusGrantsPermission(personOidsForSamePerson: Set[String], organisationOids: Set[String]): Future[Boolean] = {
    logger.info("hakemusGrantsPermission method called")
    val hakemusPermissionsForPersonOids: Set[Future[Boolean]] = personOidsForSamePerson.map(o =>
      (hakemusBasedPermissionCheckerActor ? HasPermissionForOrgs(organisationOids, o)).mapTo[Boolean]
    )
    Future.sequence(hakemusPermissionsForPersonOids).map(_.contains(true))
  }

  private def grantsPermission(resources: Seq[_], organisaatiot: Set[String]): Boolean = {
    resources.exists {
      case s: VirallinenSuoritus if organisaatiot.contains(s.myontaja) =>
        true
      case o: Opiskelija if organisaatiot.contains(o.oppilaitosOid) =>
        true
      case _ =>
        false
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

case class PermissionCheckRequest(personOidsForSamePerson: Set[String], organisationOids: Set[String]) {
  require(personOidsForSamePerson.nonEmpty, "Person oid list empty.")
  require(!personOidsForSamePerson.exists(_.isEmpty), "Blank person oid in oid list.")
  require(organisationOids.nonEmpty, "Organisation oid list empty.")
  require(!organisationOids.exists(_.isEmpty), "Blank organisation oid in organisation oid list.")
}

case class PermissionCheckResponse(accessAllowed: Option[Boolean] = None, errorMessage: Option[String] = None)