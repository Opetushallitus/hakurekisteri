package fi.vm.sade.hakurekisteri.integration.valintarekisteri

import java.util.Date

import akka.actor.Actor
import akka.pattern.pipe
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient
import fi.vm.sade.hakurekisteri.integration.henkilo.PersonOidsWithAliases
import fi.vm.sade.hakurekisteri.integration.valintarekisteri.Maksuntila.Maksuntila
import fi.vm.sade.hakurekisteri.rest.support.AuditSessionRequest
import org.joda.time.DateTime

import scala.concurrent.{ExecutionContext, Future}


class ValintarekisteriActor(restClient: VirkailijaRestClient, config: Config) extends Actor {

  implicit val ec: ExecutionContext = context.dispatcher

  private val ok = 200

  override def receive: Receive = {
    case LukuvuosimaksuQuery(hakukohdeOid, auditSession) =>
      fetchLukuvuosimaksut(hakukohdeOid, auditSession) pipeTo sender
    case ValintarekisteriQuery(personOidsWithAliases, koulutuksenAlkamiskausi) =>
      val henkiloOids = personOidsWithAliases.henkiloOids // Valintarekisteri already returns data for aliases
      fetchEnsimmainenVastaanotto(henkiloOids, koulutuksenAlkamiskausi) pipeTo sender
  }
  def fetchLukuvuosimaksut(hakukohdeOid: String, auditSession: AuditSessionRequest): Future[Seq[Lukuvuosimaksu]] = {
    restClient.postObject[Map[String, AuditSessionRequest], Seq[Lukuvuosimaksu]]("valinta-tulos-service.lukuvuosimaksu", hakukohdeOid)(ok, Map("auditSession" -> auditSession))
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

case class LukuvuosimaksuQuery(personOid: String, auditSession: AuditSessionRequest)

case class ValintarekisteriQuery(personOidsWithAliases: PersonOidsWithAliases, koulutuksenAlkamiskausi: String)

case class EnsimmainenVastaanotto(personOid: String, paattyi: Option[DateTime])
