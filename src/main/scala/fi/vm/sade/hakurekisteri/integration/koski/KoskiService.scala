package fi.vm.sade.hakurekisteri.integration.koski

import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

import akka.actor.{ActorSystem, Scheduler}
import akka.event.Logging
import fi.vm.sade.hakurekisteri.arvosana.Arvosana
import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient
import fi.vm.sade.hakurekisteri.integration.henkilo.{IOppijaNumeroRekisteri, PersonOidsWithAliases}
import fi.vm.sade.hakurekisteri.suoritus.Suoritus
import org.joda.time.LocalDate

import scala.compat.Platform
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.{FiniteDuration, _}
import scala.util.{Failure, Success, Try}

trait IKoskiService {
  def fetchChanged(personOid: String): Future[Seq[KoskiHenkilo]]
}

case class KoskiTrigger(f: (KoskiHenkiloContainer, PersonOidsWithAliases, Boolean) => Unit)

class KoskiService(virkailijaRestClient: VirkailijaRestClient, oppijaNumeroRekisteri: IOppijaNumeroRekisteri, pageSize: Int = 200)(implicit val system: ActorSystem)  {

  val fetchPersonAliases: (Seq[KoskiHenkiloContainer]) => Future[(Seq[KoskiHenkiloContainer], PersonOidsWithAliases)] = { hs: Seq[KoskiHenkiloContainer] =>
    val personOids: Seq[String] = hs.flatMap(_.henkilö.oid)
    oppijaNumeroRekisteri.enrichWithAliases(personOids.toSet).map((hs, _))
  }

  private val logger = Logging.getLogger(system, this)
  var triggers: Seq[KoskiTrigger] = Seq()

  case class SearchParams(muuttunutJälkeen: String, muuttunutEnnen: String = "2100-01-01T12:00")

  def fetchChanged(page: Int = 0, params: SearchParams): Future[Seq[KoskiHenkiloContainer]] = {
    //logger.info(s"Haetaan henkilöt ja opiskeluoikeudet Koskesta, muuttuneet välillä: " + params.muuttunutJälkeen.toString + " - " + params.muuttunutEnnen.toString)
    virkailijaRestClient.readObjectWithBasicAuth[List[KoskiHenkiloContainer]]("koski.oppija", params)(acceptedResponseCode = 200, maxRetries = 2)
  }

  //Käy läpi vanhaa dataa Koskesta ja päivittää sitä sureen. TODO: false ysit-korjaus
  def historyRepairCrawler(searchWindowStartTime: Date = new Date(Platform.currentTime - TimeUnit.DAYS.toMillis(15)),
                           timeToWaitUntilNextBatch: FiniteDuration = 10.seconds,
                           searchWindowSize: Long = TimeUnit.MINUTES.toMillis(60),
                           repairTargetTime: Date = new Date(Platform.currentTime),
                           handleFalseYsit: Boolean = false)(implicit scheduler: Scheduler): Unit = {
    if(searchWindowStartTime.getTime < repairTargetTime.getTime) {
    scheduler.scheduleOnce(timeToWaitUntilNextBatch)({
      val searchWindowEndTime: Date = new Date(searchWindowStartTime.getTime + searchWindowSize)
      fetchChanged(
        params = SearchParams(muuttunutJälkeen = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm").format(searchWindowStartTime),
          muuttunutEnnen = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm").format(searchWindowEndTime))
      ).flatMap(fetchPersonAliases).onComplete {
        case Success((henkilot, personOidsWithAliases)) =>
          logger.info(s"HistoryCrawler - muuttuneita opiskeluoikeuksia aikavälillä " + searchWindowStartTime + " - " + searchWindowEndTime + " : "  + henkilot.size + " kpl")
          Try(triggerHenkilot(henkilot, personOidsWithAliases, removeFalseYsit = handleFalseYsit)) match {
            case Failure(e) => logger.error(e, "HistoryCrawler - Exception in trigger!")
            case _ =>
          }
          historyRepairCrawler(searchWindowEndTime, timeToWaitUntilNextBatch, searchWindowSize, repairTargetTime)
        case Failure(t) =>
          logger.error(t, "HistoryCrawler - fetch data failed, retrying")
          historyRepairCrawler(searchWindowStartTime, timeToWaitUntilNextBatch, searchWindowSize, repairTargetTime)
      }
    })} else {
      logger.info(s"HistoryCrawler - koko haluttu aikaikkuna käyty läpi, lopetetaan läpikäynti.")
    }
  }

  var maximumCatchup: Long = TimeUnit.SECONDS.toMillis(30)
  //Aloitetaan 5 minuuttia menneisyydestä, päivitetään minuutin välein minuutin aikaikkunallinen dataa. HUOM: viive tietojen päivittymiselle koski -> sure runsaat 5 minuuttia oletusparametreilla.
  def processModifiedKoski(searchWindowStartTime: Date = new Date(Platform.currentTime - TimeUnit.HOURS.toMillis(1)),
                           refreshFrequency: FiniteDuration = 1.minute,
                           searchWindowSize: Long = TimeUnit.MINUTES.toMillis(1))(implicit scheduler: Scheduler): Unit = {
      scheduler.scheduleOnce(refreshFrequency)({
        var catchup = false //Estetään prosessoijaa jättäytymästä vähitellen yhä enemmän jälkeen vaihtelevien käsittelyaikojen takia
        var searchWindowEndTime: Date = new Date(searchWindowStartTime.getTime + searchWindowSize)
        if (searchWindowStartTime.getTime < (Platform.currentTime-TimeUnit.MINUTES.toMillis(5))) {
          searchWindowEndTime = new Date(searchWindowStartTime.getTime + searchWindowSize + maximumCatchup)
          catchup = true
        }
        fetchChanged(
          params = SearchParams(muuttunutJälkeen = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm").format(searchWindowStartTime),
                                muuttunutEnnen = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm").format(searchWindowEndTime))
        ).flatMap(fetchPersonAliases).onComplete {
          case Success((henkilot, personOidsWithAliases)) =>
            logger.info(s"processModifiedKoski - muuttuneita opiskeluoikeuksia aikavälillä " + searchWindowStartTime + " - " + searchWindowEndTime + ": " + henkilot.size + " kpl. Catchup " + catchup.toString)
            Try(triggerHenkilot(henkilot, personOidsWithAliases)) match {
              case Failure(e) => logger.error(e, "processModifiedKoski - Exception in trigger!")
              case _ =>
            }
            processModifiedKoski(searchWindowEndTime, refreshFrequency)
          case Failure(t) =>
            logger.error(t, "processModifiedKoski - fetching modified henkilot failed, retrying")
            processModifiedKoski(searchWindowStartTime, refreshFrequency)
        }
      })
  }

  private def triggerHenkilot(henkilot: Seq[KoskiHenkiloContainer], personOidsWithAliases: PersonOidsWithAliases, removeFalseYsit: Boolean = false): Unit =
    henkilot.foreach(henkilo => {
      triggers.foreach( trigger => trigger.f(henkilo, personOidsWithAliases, removeFalseYsit))
    })

  def addTrigger(trigger: KoskiTrigger): Unit = triggers = triggers :+ trigger
}




case class Tila(alku: String, tila: KoskiKoodi, loppu: Option[String])

case class KoskiHenkiloContainer(
                        henkilö: KoskiHenkilo,
                        opiskeluoikeudet: Seq[KoskiOpiskeluoikeus]
                        )

case class KoskiHenkilo(
                         oid: Option[String],
                         hetu: Option[String],
                         syntymäaika: Option[String],
                         etunimet: Option[String],
                         kutsumanimi: Option[String],
                         sukunimi: Option[String]) {
}
case class KoskiOpiskeluoikeus(
                 oid: String,
                 oppilaitos: KoskiOrganisaatio,
                 tila: KoskiOpiskeluoikeusjakso,
                 lisätiedot: Option[KoskiLisatiedot],
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

case class KoskiLisatiedot(erityisenTuenPäätös: Option[KoskiErityisenTuenPaatos])

case class KoskiErityisenTuenPaatos(opiskeleeToimintaAlueittain: Option[Boolean])
