package fi.vm.sade.hakurekisteri.web.opiskelija

import akka.actor.{ActorRef, ActorSystem}
import fi.vm.sade.hakurekisteri.opiskelija.{Opiskelija, OpiskelijaQuery}
import fi.vm.sade.hakurekisteri.web.rest.support.{HakurekisteriCrudCommands, HakurekisteriResource, LocalDateSupport, Security, SecuritySupport}
import org.joda.time.DateTime
import org.json4s.JValue
import org.json4s.JsonAST.{JNull}
import org.scalatra.swagger.Swagger


class OpiskelijaResource(opiskelijaActor: ActorRef)
                        (implicit sw: Swagger, s: Security, system: ActorSystem)
  extends HakurekisteriResource[Opiskelija](opiskelijaActor, OpiskelijaQuery.apply)
    with OpiskelijaSwaggerApi
    with HakurekisteriCrudCommands[Opiskelija]
    with SecuritySupport {

  override def parseResourceFromBody(user: String): Either[ValidationError, Opiskelija] = {
    try {
      val bodyValues = parsedBody.extract[Map[String, JValue]]
      val errors = checkMandatory(Seq("oppilaitosOid", "luokkataso", "luokka", "henkiloOid", "alkuPaiva"), bodyValues)
      if (errors.nonEmpty) throw ValidationError(message = errors.toString())
      Right(Opiskelija(
        oppilaitosOid = bodyValues("oppilaitosOid").extract[String],
        luokkataso = bodyValues("luokkataso").extract[String],
        luokka = bodyValues("luokka").extract[String],
        henkiloOid = bodyValues("henkiloOid").extract[String],
        alkuPaiva = DateTime.parse(bodyValues("alkuPaiva").extract[String]),
        loppuPaiva = if (bodyValues.contains("loppuPaiva") && !JNull.equals(bodyValues("loppuPaiva"))) Some(DateTime.parse(bodyValues("loppuPaiva").extract[String])) else None,
        source = user)
      )
    } catch {
      case e: ValidationError =>
        logger.error("Opiskelija resource creation failed: {}, body: {}", e.message, parsedBody)
        Left(e)
      case e: Exception =>
        logger.error("Opiskelija resource creation failed: {}, body: {} ", e, parsedBody)
        Left(ValidationError(e.getMessage, None, Some(e)))
    }
  }
}

