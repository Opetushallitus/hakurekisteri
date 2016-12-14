package fi.vm.sade.hakurekisteri.integration.valintarekisteri

import akka.actor.Actor
import akka.pattern.pipe
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient
import fi.vm.sade.hakurekisteri.integration.henkilo.PersonOidsWithAliases
import org.joda.time.DateTime

import scala.concurrent.{ExecutionContext, Future}


class ValintarekisteriActor(restClient: VirkailijaRestClient, config: Config) extends Actor {

  implicit val ec: ExecutionContext = context.dispatcher

  private val ok = 200

  override def receive: Receive = {
    case ValintarekisteriQuery(personOidsWithAliases, koulutuksenAlkamiskausi) =>
      val henkiloOids = personOidsWithAliases.henkiloOids // Valintarekisteri already returns data for aliases
      fetchEnsimmainenVastaanotto(henkiloOids, koulutuksenAlkamiskausi) pipeTo sender
  }

  def fetchEnsimmainenVastaanotto(henkiloOids: Set[String], koulutuksenAlkamiskausi: String): Future[Seq[EnsimmainenVastaanotto]] = {
    restClient.postObject[Set[String], Seq[EnsimmainenVastaanotto]]("valinta-tulos-service.ensikertalaisuus", koulutuksenAlkamiskausi)(ok, henkiloOids)
  }
}

case class ValintarekisteriQuery(personOidsWithAliases: PersonOidsWithAliases, koulutuksenAlkamiskausi: String)

case class EnsimmainenVastaanotto(personOid: String, paattyi: Option[DateTime])
