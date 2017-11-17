package fi.vm.sade.hakurekisteri.integration.koski

import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

import akka.actor.{ActorSystem, Scheduler}
import akka.event.Logging
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.hakija.Hyvaksytty
import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient
import fi.vm.sade.hakurekisteri.integration.henkilo.{IOppijaNumeroRekisteri,PersonOidsWithAliases,OppijaNumeroRekisteri}

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
//    logger.error(hs.toString())
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
      .flatMap(henkilot =>
          Future.successful(henkilot)
        )
  }

  def processModifiedKoski(modifiedAfter: Date = new Date(Platform.currentTime - TimeUnit.DAYS.toMillis(1)),
                                refreshFrequency: FiniteDuration = 1.minute)(implicit scheduler: Scheduler): Unit = {
    scheduler.scheduleOnce(refreshFrequency)({
      val lastChecked = new Date()
      fetchChanged(
        params = SearchParams(muuttunutJälkeen = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm").format(modifiedAfter))
      ).flatMap(fetchPersonAliases).onComplete {
        case Success((hakemukset)) =>
          processModifiedKoski(lastChecked, refreshFrequency)
        case Failure(t) =>
          logger.error(t, "Fetching modified hakemukset failed, retrying")
          processModifiedKoski(modifiedAfter, refreshFrequency)
      }
    })
  }

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
                         syntymäaika: String,
                         etunimet: String,
                         kutsumanimi: String,
                         sukunimi: String) {
}
case class KoskiOpiskeluoikeus(
                 oid: String,
                 oppilaitos: KoskiOrganisaatio,
                 tila: KoskiOpiskeluoikeusjakso,
                 suoritukset: Seq[KoskiSuoritus])

case class KoskiOpiskeluoikeusjakso(opiskeluoikeusjaksot: Seq[Tila])

// toimipiste, myöntäjäOrganisaatio, oppilaitos
case class KoskiOrganisaatio(oid: String)

case class KoskiSuoritus(
                  koulutusmoduuli: KoskiKoulutusmoduuli,
                  tyyppi: Option[KoskiKoodi],
                  toimipiste: KoskiOrganisaatio,
                  vahvistus: Option[KoskiVahvistus],
                  suorituskieli: Option[KoskiKieli],
                  arviointi: Option[KoskiArviointi],
                  yksilöllistettyOppimäärä: Option[Boolean],
                  osasuoritukset: Option[Seq[KoskiSuoritus]])

case class KoskiArviointi(arvosana: KoskiKoodi, hyväksytty: Boolean)

case class KoskiKoulutusmoduuli(tunniste: KoskiKoodi, koulutustyyppi: Option[KoskiKoodi])

// koulutustyyppi, tyyppi
case class KoskiKoodi(koodiarvo: String, koodistoUri: String)

case class KoskiVahvistus(päivä: String, myöntäjäOrganisaatio: KoskiOrganisaatio)

case class KoskiKieli(koodiarvo: String, koodistoUri: String)



