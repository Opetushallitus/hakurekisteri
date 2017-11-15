package fi.vm.sade.hakurekisteri.integration.koski

import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

import akka.actor.{ActorSystem, Scheduler}
import akka.event.Logging
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.hakija.Hyvaksytty
import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient
import fi.vm.sade.hakurekisteri.integration.henkilo.PersonOidsWithAliases

import scala.compat.Platform
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}
import scala.concurrent.duration._

trait IKoskiService {
  def fetchChanged(personOid: String): Future[Seq[KoskiHenkilo]]
}

case class KoskiTrigger(f: (KoskiHenkilo, PersonOidsWithAliases) => Unit)

class KoskiService(virkailijaRestClient: VirkailijaRestClient, pageSize: Int = 200)(implicit val system: ActorSystem)  {

  private val logger = Logging.getLogger(system, this)
  var triggers: Seq[KoskiTrigger] = Seq()

  case class SearchParams(muuttunutJälkeen: String = null)

  def fetchChanged(): Future[Seq[KoskiHenkilo]] = {
    fetchChanged(0, params = SearchParams())
  }

  def fetchChanged(page: Int = 0, params: SearchParams): Future[Seq[KoskiHenkilo]] = {
    virkailijaRestClient.readObjectWithBasicAuth[List[KoskiHenkilo]]("koski.oppija", params)(acceptedResponseCode = 200, maxRetries = 2)
      .flatMap(henkilot =>
        if (henkilot.length < pageSize) {
          Future.successful(henkilot)
        } else {
          fetchChanged(page + 1, params).map(henkilot ++ _)
        })
  }

  def processModifiedKoski(modifiedAfter: Date = new Date(Platform.currentTime - TimeUnit.DAYS.toMillis(2)),
                                refreshFrequency: FiniteDuration = 1.minute)(implicit scheduler: Scheduler): Unit = {
    scheduler.scheduleOnce(refreshFrequency)({
      val lastChecked = new Date()
      fetchChanged(
        params = SearchParams(muuttunutJälkeen = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm").format(modifiedAfter))
      ).onComplete {
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




case class Tila(alku: Long, tila: Lasnaolo, loppu: Option[Long])

sealed trait Lasnaolo
case class Lasna() extends Lasnaolo
case class Poissa() extends Lasnaolo
case class PoissaEiKulutaOpintoaikaa() extends Lasnaolo
case class Puuttuu() extends Lasnaolo

case class KoskiHenkilo(
                         oid: Option[String],
                         hetu: String,
                         syntymaaika: String,
                         etunimet: String,
                         kutsumanimi: Option[String],
                         sukunimi: Option[String],
                         opiskeluoikeudet: Option[Seq[KoskiOpiskeluoikeus]]) {
}
case class KoskiOpiskeluoikeus(
                 oid: String, oppilaitos: KoskiOrganisaatio, tila: Tila, suoritukset: Option[Seq[KoskiSuoritus]])

// toimipiste, myöntäjäOrganisaatio, oppilaitos
case class KoskiOrganisaatio(oid: String)

case class KoskiSuoritus(
                  koulutusmoduuli: KoskiKoulutusmoduuli,
                  tyyppi: KoskiKoodi,
                  toimipiste: Option[KoskiOrganisaatio],
                  vahvistus: Option[KoskiVahvistus],
                  suorituskieli: Option[KoskiKieli],
                  arviointi: Option[KoskiArviointi],
                  yksilöllistettyOppimäärä: Option[Boolean],
                  osasuoritukset: Option[Seq[KoskiSuoritus]])

case class KoskiArviointi(arvosana: KoskiKoodi, hyväksytty: Boolean)

case class KoskiKoulutusmoduuli(koulutustyyppi: KoskiTunniste)

case class KoskiTunniste(tunniste: KoskiKoodi, kieli: KoskiKieli)

// koulutustyyppi, tyyppi
case class KoskiKoodi(koodiarvo: String, koodistoUri: String)

case class KoskiVahvistus(päivä: String, myöntäjäOrganisaatio: KoskiOrganisaatio)

case class KoskiKieli(koodiarvo: String, koodistoUri: String)



