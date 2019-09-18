package fi.vm.sade.hakurekisteri.web.opiskelija

import akka.actor.{ActorRef, ActorSystem}
import fi.vm.sade.hakurekisteri.opiskelija
import fi.vm.sade.hakurekisteri.opiskelija.{Opiskelija, OpiskelijaQuery}
import fi.vm.sade.hakurekisteri.web.rest.support.{HakurekisteriCrudCommands, HakurekisteriResource, LocalDateSupport, Security, SecuritySupport}
import org.joda.time.DateTime
import org.json4s.JValue
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

      //todo either remove this or implement elsewhere also
      val mandatoryFields = Seq("oppilaitosOid", "luokkataso", "luokka", "henkiloOid", "alkuPaiva")
      mandatoryFields.foreach(f =>
        if (!bodyValues.contains(f)) {
          logger.error("failed: " + f)
          throw ValidationError(message = f + " on pakollinen tieto", field = Some(f))
        })

      Right(Opiskelija(
        oppilaitosOid = bodyValues("oppilaitosOid").extract[String],
        luokkataso = bodyValues("luokkataso").extract[String],
        luokka = bodyValues("luokka").extract[String],
        henkiloOid = bodyValues("henkiloOid").extract[String],
        alkuPaiva = DateTime.parse(bodyValues("alkuPaiva").extract[String]),
        loppuPaiva = if (bodyValues.contains("loppuPaiva")) Some(DateTime.parse(bodyValues("loppuPaiva").extract[String])) else None,
        source = user)
      )
    } catch {
      case e: Exception =>
        logger.error("Opiskelija resource creation failed from {} : {} ", parsedBody, e)
        Left(ValidationError(e.getMessage, None, Some(e)))
    }
  }
}

