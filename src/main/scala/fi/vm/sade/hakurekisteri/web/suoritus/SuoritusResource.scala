package fi.vm.sade.hakurekisteri.web.suoritus

import java.util.concurrent.TimeUnit

import _root_.akka.pattern.ask
import akka.actor.{ActorRef, ActorSystem}
import fi.vm.sade.hakurekisteri.KomoOids
import fi.vm.sade.hakurekisteri.integration.koodisto.{GetKoodistoKoodiArvot, KoodistoActorRef, KoodistoKoodiArvot}
import fi.vm.sade.hakurekisteri.integration.parametrit.{IsRestrictionActive, ParameterActor, ParametritActorRef}
import fi.vm.sade.hakurekisteri.rest.support.User
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, SuoritusQuery, VirallinenSuoritus}
import fi.vm.sade.hakurekisteri.web.rest.support.{ResourceNotEnabledException, _}
import org.scalatra.NotFound
import org.scalatra.swagger.Swagger

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

class SuoritusResource
(suoritusRekisteriActor: ActorRef, parameterActor: ParametritActorRef, koodistoActor: KoodistoActorRef)(implicit sw: Swagger, s: Security, system: ActorSystem)
  extends HakurekisteriResource[Suoritus, CreateSuoritusCommand](suoritusRekisteriActor, SuoritusQuery(_)) with SuoritusSwaggerApi with HakurekisteriCrudCommands[Suoritus,
    CreateSuoritusCommand] with SecuritySupport with IncidentReporting{

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

  registerCommand[CreateSuoritusCommand](CreateSuoritusCommand(koodistoKielet))

  incident {
    case ResourceNotEnabledException => (id) => NotFound(IncidentReport(id, "Suorituksen muokkaaminen ei tällä hetkellä ole mahdollista"))
  }
}
