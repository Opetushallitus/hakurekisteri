package fi.vm.sade.hakurekisteri.web.suoritus

import java.util.concurrent.TimeUnit

import _root_.akka.pattern.ask
import akka.actor.{ActorRef, ActorSystem}
import fi.vm.sade.hakurekisteri.KomoOids
import fi.vm.sade.hakurekisteri.integration.koodisto.{GetKoodistoKoodiArvot, KoodistoActorRef, KoodistoKoodiArvot}
import fi.vm.sade.hakurekisteri.integration.parametrit.{IsRestrictionActive, ParameterActor, ParametritActorRef}
import fi.vm.sade.hakurekisteri.rest.support.User
import fi.vm.sade.hakurekisteri.suoritus.yksilollistaminen.Yksilollistetty
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, SuoritusQuery, VirallinenSuoritus, yksilollistaminen}
import fi.vm.sade.hakurekisteri.web.rest.support.{ResourceNotEnabledException, _}
import org.json4s.JValue
import org.scalatra.NotFound
import org.scalatra.swagger.Swagger

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

class SuoritusResource
(suoritusRekisteriActor: ActorRef, parameterActor: ParametritActorRef, koodistoActor: KoodistoActorRef)(implicit sw: Swagger, s: Security, system: ActorSystem)
  extends HakurekisteriResource[Suoritus](suoritusRekisteriActor, SuoritusQuery(_)) with SuoritusSwaggerApi with HakurekisteriCrudCommands[Suoritus] with SecuritySupport with IncidentReporting with LocalDateSupport {

  override def createEnabled(resource: Suoritus, user: Option[User]) = {
    resource match {
      case s: VirallinenSuoritus =>
        if (KomoOids.toisenAsteenVirkailijanKoulutukset.contains(s.komo) && !user.get.isAdmin) {
          (parameterActor.actor ? IsRestrictionActive(ParameterActor.opoUpdateGraduation))
            .mapTo[Boolean]
            .map(x => !x)
        }
        else Future.successful(true)
      case _ => Future.successful(true)
    }
  }

  private var koodistoKielet = Await.result((koodistoActor.actor ? GetKoodistoKoodiArvot("kieli")).
    mapTo[KoodistoKoodiArvot].
    map(arvot => arvot.arvot), Duration(5, TimeUnit.SECONDS))

  koodistoKielet = koodistoKielet ++ koodistoKielet.map(_.toLowerCase())

  override def updateEnabled(resource: Suoritus, user: Option[User]) = createEnabled(resource, user)

  incident {
    case ResourceNotEnabledException => (id) => NotFound(IncidentReport(id, "Suorituksen muokkaaminen ei tällä hetkellä ole mahdollista"))
  }

  override def parseResourceFromBody(user: String): Either[ValidationError, Suoritus] = {
    try {
      val bodyValues = parsedBody.extract[Map[String, JValue]]
      val errors = checkMandatory(Seq("komo", "myontaja", "tila", "valmistuminen", "henkiloOid", "suoritusKieli"), bodyValues)
      if (errors.nonEmpty) throw ValidationError(message = errors.toString())
      val vahv = if (bodyValues.contains("vahvistettu")) bodyValues("vahvistettu").extract[Boolean] else false
      val yks = if (bodyValues.contains("yksilollistaminen")) bodyValues("yksilollistaminen").extract[Yksilollistetty] else yksilollistaminen.Ei
      val s = VirallinenSuoritus(
        komo = bodyValues("komo").extract[String],
        myontaja = bodyValues("myontaja").extract[String],
        tila = bodyValues("tila").extract[String],
        valmistuminen = jsonToLocalDate(bodyValues("valmistuminen")).get,
        henkilo = bodyValues("henkiloOid").extract[String],
        yksilollistaminen = yks,
        suoritusKieli = bodyValues("suoritusKieli").extract[String],
        vahv = vahv,
        lahde = user,
        lahdeArvot = Map()
      )
      logger.info("parsed s: " + s)
      logger.info("vahvistus: " + vahv + ", source: " + bodyValues.get("vahvistettu"))
      logger.info("yks: " + yks + ", source: " + bodyValues.get("yksilollistaminen"))
      Right(s)
    } catch {
      case e: ValidationError =>
        logger.error("Suoritus resource creation failed: {}, body: {}", e.message, parsedBody)
        Left(e)
      case e: Exception =>
        logger.error("Suoritus resource creation failed: {}, body: {} ", e, parsedBody)
        Left(ValidationError(e.getMessage, None, Some(e)))
    }
  }
}
