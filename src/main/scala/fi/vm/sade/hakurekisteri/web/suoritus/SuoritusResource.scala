package fi.vm.sade.hakurekisteri.web.suoritus

import _root_.akka.pattern.ask
import akka.actor.{ActorRef, ActorSystem}
import fi.vm.sade.hakurekisteri.KomoOids
import fi.vm.sade.hakurekisteri.integration.henkilo.IOppijaNumeroRekisteri
import fi.vm.sade.hakurekisteri.integration.parametrit.{IsRestrictionActive, ParameterActor}
import fi.vm.sade.hakurekisteri.rest.support.{Query, User}
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, SuoritusQuery, VirallinenSuoritus}
import fi.vm.sade.hakurekisteri.web.batchimport.ResourceNotEnabledException
import fi.vm.sade.hakurekisteri.web.rest.support._
import org.scalatra.NotFound
import org.scalatra.swagger.Swagger

import scala.concurrent.Future
import scala.util.Try

class SuoritusResource(suoritusRekisteriActor: ActorRef, parameterActor: ActorRef, oppijaNumeroRekisteri: IOppijaNumeroRekisteri)
                      (implicit sw: Swagger, s: Security, system: ActorSystem)
  extends HakurekisteriResource[Suoritus, CreateSuoritusCommand](suoritusRekisteriActor, p => ??? /* Not in use */) with SuoritusSwaggerApi with HakurekisteriCrudCommands[Suoritus, CreateSuoritusCommand] with SecuritySupport with IncidentReporting{

  override def queryResource(user: Option[User], t0: Long): Product with Serializable = {
    (Try {
      SuoritusQuery(params, oppijaNumeroRekisteri.enrichWithAliases _)
    } map ((q: Query[Suoritus]) => ResourceQuery(q, user, t0)) recover {
      case e: Exception =>
        logger.error(e, "Bad query: " + params)
        throw new IllegalArgumentException("illegal query params")
    }).get
  }

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
