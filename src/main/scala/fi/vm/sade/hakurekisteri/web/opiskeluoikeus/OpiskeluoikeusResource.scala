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

  //aika: Ajanjakso,
  //                          henkiloOid: String,
  //                          komo: String,
  //                          myontaja: String,
  //                          source : String

  //  val alkuPvm: Field[String] = asType[String]("aika.alku").required
  //  val loppuPvm: Field[Option[String]] = asType[Option[String]]("aika.loppu").optional(None)
  //  val henkiloOid: Field[String] = asType[String]("henkiloOid").notBlank
  //  val komo: Field[String] = asType[String]("komo").notBlank
  //  val myontaja: Field[String] = asType[String]("myontaja").notBlank
  //
  //  override def toResource(user: String): Opiskeluoikeus = Opiskeluoikeus(
  //  DateTime.parse(alkuPvm.value.get)
  //  , loppuPvm.value.get.map(DateTime.parse)
  //  , henkiloOid.value.get, komo.value.get, myontaja.value.get, source = user)

  override def parseResourceFromBody(user: String): Either[ValidationError, Opiskeluoikeus] = {
    try {
      val bodyValues = parsedBody.extract[Map[String, JValue]]
      val errors = checkMandatory(Seq("henkiloOid", "komo", "myontaja", "aika"), bodyValues)
      if (errors.nonEmpty) throw ValidationError(message = errors.toString())

      val aika = bodyValues("aika").extract[Map[String, JValue]]
      logger.info("aika: " + aika)
      val l = aika.get("loppu")

      val alkuPaiva = jsonToLocalDate(aika("alku")).get
      val loppuPaiva: Option[LocalDate] = if (l.isDefined) jsonToLocalDate(l.get) else None
      logger.info("alku " + alkuPaiva + ", loppu " + loppuPaiva)
      Right(Opiskeluoikeus(
        alkuPaiva,
        loppuPaiva,
        bodyValues("henkiloOid").extract[String],
        bodyValues("komo").extract[String],
        bodyValues("myontaja").extract[String],
        source = user
      ))
    } catch {
      case e: ValidationError =>
        logger.error("Opiskeluoikeus resource creation failed: {}, body: {}", e.message, parsedBody)
        Left(e)
      case e: Exception =>
        logger.error("Opiskeluoikeus resource creation failed: {}, body: {} ", e, parsedBody)
        Left(ValidationError(e.getMessage, None, Some(e)))
    }
  }
}
