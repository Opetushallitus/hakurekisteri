package fi.vm.sade.hakurekisteri.integration.valintarekisteri

import java.util.Date

import akka.actor.{Actor, ActorRef}
import akka.pattern.pipe
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.{ExecutorUtil, VirkailijaRestClient}
import fi.vm.sade.hakurekisteri.integration.henkilo.PersonOidsWithAliases
import fi.vm.sade.hakurekisteri.integration.valintarekisteri.Maksuntila.Maksuntila
import fi.vm.sade.hakurekisteri.rest.support.AuditSessionRequest
import org.joda.time.DateTime
import support.TypedActorRef

import scala.concurrent.{ExecutionContext, Future}


class ValintarekisteriActor(restClient: VirkailijaRestClient, config: Config) extends Actor {

  implicit val ec: ExecutionContext = ExecutorUtil.createExecutor(config.integrations.asyncOperationThreadPoolSize, getClass.getSimpleName)

  private val ok = 200

  override def receive: Receive = {
    case LukuvuosimaksuQuery(hakukohdeOids, auditSession) =>
      fetchLukuvuosimaksut(hakukohdeOids, auditSession) pipeTo sender
    case ValintarekisteriQuery(personOidsWithAliases, koulutuksenAlkamiskausi) =>
      val henkiloOids = personOidsWithAliases.henkiloOids // Valintarekisteri already returns data for aliases
      fetchEnsimmainenVastaanotto(henkiloOids, koulutuksenAlkamiskausi) pipeTo sender
  }
  def fetchLukuvuosimaksut(hakukohdeOids: Set[String], auditSession: AuditSessionRequest): Future[Seq[Lukuvuosimaksu]] = {
    val request = Map("hakukohdeOids" -> hakukohdeOids, "auditSession" -> auditSession)
    restClient.postObject[AnyRef, Seq[Lukuvuosimaksu]]("valinta-tulos-service.lukuvuosimaksu.bulk")(ok, request)
  }
  def fetchEnsimmainenVastaanotto(henkiloOids: Set[String], koulutuksenAlkamiskausi: String): Future[Seq[EnsimmainenVastaanotto]] = {
    restClient.postObject[Set[String], Seq[EnsimmainenVastaanotto]]("valinta-tulos-service.ensikertalaisuus", koulutuksenAlkamiskausi)(ok, henkiloOids)
  }
}



object Maksuntila extends Enumeration {
  type Maksuntila = Value
  val maksettu = Value("MAKSETTU")
  val maksamatta = Value("MAKSAMATTA")
  val vapautettu = Value("VAPAUTETTU")
}

case class Lukuvuosimaksu(personOid: String, hakukohdeOid: String, maksuntila: Maksuntila, muokkaaja: String, luotu: Date)

case class LukuvuosimaksuQuery(hakukohdeOids: Set[String], auditSession: AuditSessionRequest)

case class ValintarekisteriQuery(personOidsWithAliases: PersonOidsWithAliases, koulutuksenAlkamiskausi: String)

case class EnsimmainenVastaanotto(personOid: String, paattyi: Option[DateTime])

case class ValintarekisteriActorRef(actor: ActorRef) extends TypedActorRef
