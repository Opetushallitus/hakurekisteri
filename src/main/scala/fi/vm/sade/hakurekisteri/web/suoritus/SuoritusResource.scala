package fi.vm.sade.hakurekisteri.web.suoritus

import _root_.akka.pattern.ask
import akka.actor.{ActorRef, ActorSystem}
import fi.vm.sade.hakurekisteri.KomoOids
import fi.vm.sade.hakurekisteri.integration.parametrit.{IsRestrictionActive, ParameterActor}
import fi.vm.sade.hakurekisteri.rest.support.User
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, SuoritusQuery, VirallinenSuoritus}
import fi.vm.sade.hakurekisteri.web.rest.support.{ResourceNotEnabledException, _}
import org.scalatra.NotFound
import org.scalatra.swagger.Swagger

import scala.concurrent.Future

class SuoritusResource
(suoritusRekisteriActor: ActorRef, parameterActor: ActorRef)(implicit sw: Swagger, s: Security, system: ActorSystem)
  extends HakurekisteriResource[Suoritus, CreateSuoritusCommand](suoritusRekisteriActor, SuoritusQuery(_)) with SuoritusSwaggerApi with HakurekisteriCrudCommands[Suoritus,
    CreateSuoritusCommand] with SecuritySupport with IncidentReporting{

  override def createEnabled(resource: Suoritus, user: Option[User]) = {
    resource match {
      case s: VirallinenSuoritus =>
        if (KomoOids.toisenAsteenVirkailijanKoulutukset.contains(s.komo) && !user.get.isAdmin) {
          (parameterActor ? IsRestrictionActive(ParameterActor.opoUpdateGraduation))
            .mapTo[Boolean]
            .map(x => !x)
        }
        else Future.successful(true)
      case _ => Future.successful(true)
    }
  }

  override def updateEnabled(resource: Suoritus, user: Option[User]) = createEnabled(resource, user)

  incident {
    case ResourceNotEnabledException => (id) => NotFound(IncidentReport(id, "Suorituksen muokkaaminen ei tällä hetkellä ole mahdollista"))
  }
}
