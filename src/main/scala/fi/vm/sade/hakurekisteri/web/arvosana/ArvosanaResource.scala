package fi.vm.sade.hakurekisteri.web.arvosana

import java.util.UUID

import _root_.akka.pattern.ask
import akka.actor.{ActorRef, ActorSystem}
import fi.vm.sade.hakurekisteri.KomoOids
import fi.vm.sade.hakurekisteri.arvosana.{Arvio, Arvosana, ArvosanaQuery}
import fi.vm.sade.hakurekisteri.rest.support.User
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, VirallinenSuoritus}
import fi.vm.sade.hakurekisteri.web.rest.support.{HakurekisteriCrudCommands, HakurekisteriResource, LocalDateSupport, Security, SecuritySupport}
import org.json4s.{DefaultFormats, JValue}
import org.scalatra.swagger.Swagger

import scala.concurrent.Future

class ArvosanaResource(arvosanaActor: ActorRef, suoritusActor: ActorRef)
                      (implicit sw: Swagger, s: Security, system: ActorSystem)
  extends HakurekisteriResource[Arvosana](arvosanaActor, ArvosanaQuery(_))
    with ArvosanaSwaggerApi
    with HakurekisteriCrudCommands[Arvosana]
    with SecuritySupport
    with LocalDateSupport {

  override def createEnabled(resource: Arvosana, user: Option[User]): Future[Boolean] =
    (suoritusActor ? resource.suoritus).mapTo[Option[Suoritus]].flatMap {
      case Some(v: VirallinenSuoritus) if v.komo == KomoOids.ammatillisenKielikoe => Future.successful(user.exists(_.isAdmin))
      case Some(_) => Future.successful(user.exists(_.username == resource.source))
      case _ => super.updateEnabled(resource, user)
    }

  override def updateEnabled(resource: Arvosana, user: Option[User]): Future[Boolean] =
    (suoritusActor ? resource.suoritus).mapTo[Option[Suoritus]].flatMap {
      case Some(v: VirallinenSuoritus) if v.komo == KomoOids.ammatillisenKielikoe => Future.successful(user.exists(_.isAdmin))
      case _ => super.updateEnabled(resource, user)
    }

  override def parseResourceFromBody(user: String): Either[ValidationError, Arvosana] = {
    logger.info("PARSING ARVOSANA")

    try {
      logger.info("pbody: " + parsedBody)
      val bodyValues = parsedBody.extract[Map[String, JValue]]
      logger.info("arvosana " + bodyValues)
      val arvio = bodyValues("arvio").extract[Map[String, JValue]]

      val jarj: Option[Int] = bodyValues.get("jarjestys").map(_.extract[Int])
      logger.info("jarjestys: " + jarj)

      val a = Arvosana(
        suoritus = UUID.fromString(bodyValues("suoritus").extract[String]),
        arvio = Arvio(arvio("arvosana").extract[String], arvio("asteikko").extract[String], arvio.get("pisteet").map(_.extract[Int])),
        aine = bodyValues("aine").extract[String],
        lisatieto = bodyValues.get("lisatieto").map(_.extract[String]),
        valinnainen = if (bodyValues.contains("valinnainen")) bodyValues("valinnainen").extract[Boolean] else false,
        myonnetty = if (bodyValues.contains("myonnetty")) jsonToLocalDate(bodyValues("myonnetty")) else None,
        source = if (bodyValues.contains("source")) bodyValues("source").extract[String] else user,
        lahdeArvot = Map(),
        jarjestys = bodyValues.get("jarjestys").map(_.extract[Int])
      )
      logger.info("Success, returning parsed arvosana: " + a)
      Right(a)
    } catch {
      case e: Exception =>
        logger.error("Arvosana resource creation failed from {} : {} ", parsedBody, e)
      Left(ValidationError("Arvosana parsing failed", None, Some(e)))
    }
  }
}
