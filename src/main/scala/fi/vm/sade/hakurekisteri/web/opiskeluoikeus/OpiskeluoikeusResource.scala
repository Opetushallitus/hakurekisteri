package fi.vm.sade.hakurekisteri.web.opiskeluoikeus

import akka.actor.{ActorRef, ActorSystem}
import fi.vm.sade.hakurekisteri.opiskeluoikeus.{Opiskeluoikeus, OpiskeluoikeusQuery}
import fi.vm.sade.hakurekisteri.web.rest.support.{HakurekisteriCrudCommands, HakurekisteriResource, LocalDateSupport, Security, SecuritySupport}
import org.joda.time.{DateTime, LocalDate}
import org.json4s.JValue
import org.scalatra.swagger.Swagger

class OpiskeluoikeusResource (opiskeluoikeusActor: ActorRef)
                             (implicit sw: Swagger, s: Security, system: ActorSystem)
  extends HakurekisteriResource[Opiskeluoikeus](opiskeluoikeusActor, OpiskeluoikeusQuery.apply)
    with OpiskeluoikeusSwaggerApi
    with HakurekisteriCrudCommands[Opiskeluoikeus]
    with SecuritySupport
    with LocalDateSupport {

  override def parseResourceFromBody(user: String): Either[ValidationError, Opiskeluoikeus] = {
    try {
      val bodyValues = parsedBody.extract[Map[String, JValue]]
      val errors = checkMandatory(Seq("henkiloOid", "komo", "myontaja", "aika"), bodyValues)
      if (errors.nonEmpty) throw ValidationError(message = errors.toString())

      val aika = bodyValues("aika").extract[Map[String, JValue]]
      Right(Opiskeluoikeus(
        jsonToLocalDate(aika("alku")).get,
        aika.get("loppu").flatMap(jsonToLocalDate(_)),
        bodyValues("henkiloOid").extract[String],
        bodyValues("komo").extract[String],
        bodyValues("myontaja").extract[String],
        source = user
      ))
    } catch {
      case e: ValidationError =>
        logger.error("Opiskeluoikeus resource validation failed: {}, body: {}", e.message, parsedBody)
        Left(e)
      case e: Exception =>
        logger.error("Opiskeluoikeus resource creation failed: {}, body: {} ", e, parsedBody)
        Left(ValidationError(e.getMessage, None, Some(e)))
    }
  }
}
