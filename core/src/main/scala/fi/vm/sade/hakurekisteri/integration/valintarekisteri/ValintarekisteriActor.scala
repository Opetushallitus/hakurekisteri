package fi.vm.sade.hakurekisteri.integration.valintarekisteri

import java.net.URLEncoder

import akka.actor.Actor
import akka.pattern.pipe
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient
import org.joda.time.DateTime

import scala.concurrent.{ExecutionContext, Future}


class ValintarekisteriActor(restClient: VirkailijaRestClient, config: Config)  extends Actor {

  implicit val ec: ExecutionContext = context.dispatcher

  override def receive: Receive = {
    case ValintarekisteriQuery(henkiloOid, koulutuksenAlkamiskausi) => pipe(fetchEnsimmainenVastaanotto(henkiloOid, koulutuksenAlkamiskausi)) pipeTo sender
  }

  def fetchEnsimmainenVastaanotto(henkiloOid: String, koulutuksenAlkamiskausi: String): Future[Option[DateTime]] = {
    restClient.readObject[EnsimmainenVastaanotto]("valintarekisteri.ensikertalaisuus", henkiloOid, koulutuksenAlkamiskausi)(200, 2).map(_.paattyi)
  }
}

case class ValintarekisteriQuery(henkiloOid: String, koulutuksenAlkamiskausi: String)

case class EnsimmainenVastaanotto(oid: String, paattyi: Option[DateTime])
