package fi.vm.sade.hakurekisteri.integration.valintarekisteri

import akka.actor.Actor
import akka.pattern.pipe
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient
import org.joda.time.DateTime

import scala.concurrent.{ExecutionContext, Future}


class ValintarekisteriActor(restClient: VirkailijaRestClient, config: Config)  extends Actor {

  implicit val ec: ExecutionContext = context.dispatcher

  override def receive: Receive = {
    case henkiloOid: String => pipe(fetchEnsimmainenVastaanotto(henkiloOid)) pipeTo sender
  }

  def fetchEnsimmainenVastaanotto(henkiloOid: String): Future[Option[DateTime]] = restClient.readObject[EnsimmainenVastaanotto](s"/ensikertalaisuus/$henkiloOid", 200).map(_.paattyi)

}


case class EnsimmainenVastaanotto(oid: String, paattyi: Option[DateTime])
