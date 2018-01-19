package fi.vm.sade.hakurekisteri.integration.koski

import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

import akka.actor.{ActorSystem, Scheduler}
import akka.event.Logging
import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient
import fi.vm.sade.hakurekisteri.integration.henkilo.{IOppijaNumeroRekisteri, PersonOidsWithAliases}

import scala.compat.Platform
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}
import scala.concurrent.duration._

trait IKoskiService {
  def fetchChanged(personOid: String): Future[Seq[KoskiHenkilo]]
}

case class KoskiTrigger(f: (KoskiHenkiloContainer, PersonOidsWithAliases) => Unit)

class KoskiService(virkailijaRestClient: VirkailijaRestClient, oppijaNumeroRekisteri: IOppijaNumeroRekisteri, pageSize: Int = 200)(implicit val system: ActorSystem)  {

  val fetchPersonAliases: (Seq[KoskiHenkiloContainer]) => Future[(Seq[KoskiHenkiloContainer], PersonOidsWithAliases)] = { hs: Seq[KoskiHenkiloContainer] =>
    val personOids: Seq[String] = hs.flatMap(_.henkilö.oid)
    oppijaNumeroRekisteri.enrichWithAliases(personOids.toSet).map((hs, _))
  }

  private val logger = Logging.getLogger(system, this)
  var triggers: Seq[KoskiTrigger] = Seq()

  case class SearchParams(muuttunutJälkeen: String = null)

  def fetchChanged(): Future[Seq[KoskiHenkiloContainer]] = {
    fetchChanged(0, params = SearchParams())
  }

  def fetchChanged(page: Int = 0, params: SearchParams): Future[Seq[KoskiHenkiloContainer]] = {
    virkailijaRestClient.readObjectWithBasicAuth[List[KoskiHenkiloContainer]]("koski.oppija", params)(acceptedResponseCode = 200, maxRetries = 2)
  }

  def processModifiedKoski(modifiedAfter: Date = new Date(Platform.currentTime - TimeUnit.DAYS.toMillis(1)),
                                refreshFrequency: FiniteDuration = 1.minute)(implicit scheduler: Scheduler): Unit = {
    scheduler.scheduleOnce(refreshFrequency)({
      val lastChecked = new Date()
      fetchChanged(
        params = SearchParams(muuttunutJälkeen = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm").format(modifiedAfter))
      ).flatMap(fetchPersonAliases).onComplete {
        case Success((henkilot, personOidsWithAliases)) =>
          Try(triggerHenkilot(henkilot, personOidsWithAliases)) match {
            case Failure(e) => logger.error(e, "Exception in trigger!")
            case _ =>
          }
          processModifiedKoski(lastChecked, refreshFrequency)
        case Failure(t) =>
          logger.error(t, "Fetching modified henkilot failed, retrying")
          processModifiedKoski(modifiedAfter, refreshFrequency)
      }
    })
  }

  private def triggerHenkilot(henkilot: Seq[KoskiHenkiloContainer], personOidsWithAliases: PersonOidsWithAliases) =
    henkilot.foreach(henkilo =>
      triggers.foreach( trigger => trigger.f(henkilo, personOidsWithAliases))
    )

  def addTrigger(trigger: KoskiTrigger) = triggers = triggers :+ trigger
}




case class Tila(alku: String, tila: KoskiKoodi, loppu: Option[String])

case class KoskiHenkiloContainer(
                        henkilö: KoskiHenkilo,
                        opiskeluoikeudet: Seq[KoskiOpiskeluoikeus]
                        )

case class KoskiHenkilo(
                         oid: Option[String],
                         hetu: String,
                         syntymäaika: Option[String],
                         etunimet: String,
                         kutsumanimi: String,
                         sukunimi: String) {
}
case class KoskiOpiskeluoikeus(
                 oid: String,
                 oppilaitos: KoskiOrganisaatio,
                 tila: KoskiOpiskeluoikeusjakso,
                 suoritukset: Seq[KoskiSuoritus])

case class KoskiOpiskeluoikeusjakso(opiskeluoikeusjaksot: Seq[KoskiTila])

case class KoskiTila(alku: String, tila:KoskiKoodi)

case class KoskiOrganisaatio(oid: String)

case class KoskiSuoritus(
                  luokka: Option[String],
                  koulutusmoduuli: KoskiKoulutusmoduuli,
                  tyyppi: Option[KoskiKoodi],
                  kieli: Option[KoskiKieli],
                  pakollinen: Option[Boolean],
                  toimipiste: Option[KoskiOrganisaatio],
                  vahvistus: Option[KoskiVahvistus],
                  suorituskieli: Option[KoskiKieli],
                  arviointi: Option[Seq[KoskiArviointi]],
                  yksilöllistettyOppimäärä: Option[Boolean],
                  osasuoritukset: Seq[KoskiOsasuoritus],
                  ryhmä: Option[String],
                  alkamispäivä: Option[String])

case class KoskiOsasuoritus(
                 koulutusmoduuli: KoskiKoulutusmoduuli,
                 tyyppi: KoskiKoodi,
                 arviointi: Seq[KoskiArviointi],
                 pakollinen: Option[Boolean],
                 yksilöllistettyOppimäärä: Option[Boolean]
             )

case class KoskiArviointi(arvosana: KoskiKoodi, hyväksytty: Option[Boolean])

case class KoskiKoulutusmoduuli(tunniste: Option[KoskiKoodi], kieli: Option[KoskiKieli], koulutustyyppi: Option[KoskiKoodi], laajuus: Option[KoskiValmaLaajuus])

case class KoskiValmaLaajuus(arvo: Option[BigDecimal], yksikkö: KoskiKoodi)

case class KoskiKoodi(koodiarvo: String, koodistoUri: String)

case class KoskiVahvistus(päivä: String, myöntäjäOrganisaatio: KoskiOrganisaatio)

case class KoskiKieli(koodiarvo: String, koodistoUri: String)



