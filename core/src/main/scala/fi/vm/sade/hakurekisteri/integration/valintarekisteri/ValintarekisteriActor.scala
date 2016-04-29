package fi.vm.sade.hakurekisteri.integration.valintarekisteri

import akka.actor.Actor
import akka.pattern.pipe
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient
import org.joda.time.DateTime

import scala.concurrent.{ExecutionContext, Future}


class ValintarekisteriActor(restClient: VirkailijaRestClient, config: Config) extends Actor {

  implicit val ec: ExecutionContext = context.dispatcher

  private val ok = 200

  override def receive: Receive = {
    case ValintarekisteriQuery(henkiloOids, koulutuksenAlkamiskausi) =>
      fetchEnsimmainenVastaanotto(henkiloOids, koulutuksenAlkamiskausi) pipeTo sender
  }

  def fetchEnsimmainenVastaanotto(henkiloOids: Set[String], koulutuksenAlkamiskausi: String): Future[Seq[EnsimmainenVastaanotto]] = {
    if (henkiloOids.size == 1) {
      restClient.readObject("valintarekisteri.single-ensikertalaisuus", henkiloOids.head, koulutuksenAlkamiskausi)(ok, maxRetries = 3)
    } else {
      restClient.postObject[Set[String], Seq[EnsimmainenVastaanotto]]("valintarekisteri.ensikertalaisuus", koulutuksenAlkamiskausi)(ok, henkiloOids)
    }
  }
}

case class ValintarekisteriQuery(henkiloOids: Set[String], koulutuksenAlkamiskausi: String)

case class EnsimmainenVastaanotto(personOid: String, paattyi: Option[DateTime])
