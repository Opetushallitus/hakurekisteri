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

case class KoskiTrigger(f: (KoskiHenkiloContainer, PersonOidsWithAliases) => Unit)

class KoskiService(virkailijaRestClient: VirkailijaRestClient, oppijaNumeroRekisteri: IOppijaNumeroRekisteri, pageSize: Int = 200)(implicit val system: ActorSystem)  {

  val fetchPersonAliases: (Seq[KoskiHenkiloContainer]) => Future[(Seq[KoskiHenkiloContainer], PersonOidsWithAliases)] = { hs: Seq[KoskiHenkiloContainer] =>
    val personOids: Seq[String] = hs.flatMap(_.henkilö.oid)
    oppijaNumeroRekisteri.enrichWithAliases(personOids.toSet).map((hs, _))
  }

  private val logger = Logging.getLogger(system, this)
  var triggers: Seq[KoskiTrigger] = Seq()

  case class SearchParams(muuttunutJälkeen: String, muuttunutEnnen: String = "2100-01-01T12:00")

  def fetchChanged(page: Int = 0, params: SearchParams): Future[Seq[KoskiHenkiloContainer]] = {
    logger.info(s"Haetaan henkilöt ja opiskeluoikeudet Koskesta, muuttuneet välillä: " + params.muuttunutJälkeen.toString + " - " + params.muuttunutEnnen.toString)
    virkailijaRestClient.readObjectWithBasicAuth[List[KoskiHenkiloContainer]]("koski.oppija", params)(acceptedResponseCode = 200, maxRetries = 2)
  }

  var totalFalseDataFound: Long = 0
  var processedHenkilos: Map[String, Seq[(Suoritus, Seq[Arvosana], String, LocalDate)]] = Map[String, Seq[(Suoritus, Seq[Arvosana], String, LocalDate)]]()

  def seekOldAndFalseData(modifiedAfter: Date = new Date(Platform.currentTime - TimeUnit.DAYS.toMillis(14)),
                           refreshFrequency: FiniteDuration = 10.seconds,
                           searchWindowSize: Long = TimeUnit.HOURS.toMillis(3))(implicit scheduler: Scheduler): Unit = {
    if(modifiedAfter.getTime < Platform.currentTime ) {
    scheduler.scheduleOnce(refreshFrequency)({
      val modifiedBefore: Date = new Date(modifiedAfter.getTime + searchWindowSize)
      fetchChanged(
        params = SearchParams(muuttunutJälkeen = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm").format(modifiedAfter),
          muuttunutEnnen = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm").format(modifiedBefore))
      ).flatMap(fetchPersonAliases).onComplete {
        case Success((henkilot, personOidsWithAliases)) =>
          logger.info(s"--- Huonoa dataa löydetty " + henkilot.size + " kpl")
          totalFalseDataFound += henkilot.size
          /*Try(triggerHenkilot(henkilot, personOidsWithAliases)) match {
            case Failure(e) => logger.error(e, "Exception in trigger!")
            case _ =>
          }*/
          henkilot.foreach(h => processedHenkilos.+(h.henkilö.oid.getOrElse("Henkilöllä ei oidia") -> KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(h)))
          logger.info(s"--- Prosessointi valmis, size: " + processedHenkilos.size)
          if(processedHenkilos.size < 10) {
            logger.info("--- Processed henkilös map: " + processedHenkilos.toString())
          }
          seekOldAndFalseData(modifiedBefore, 10.seconds)
        case Failure(t) =>
          logger.error(t, "--- Seek old and false data failed, retrying")
          seekOldAndFalseData(modifiedAfter, refreshFrequency)
      }
    })}
  }


  def processModifiedKoski(modifiedAfter: Date = new Date(Platform.currentTime - TimeUnit.HOURS.toMillis(3)),
                           refreshFrequency: FiniteDuration = 1.minute,
                           searchWindowSize: Long = TimeUnit.MINUTES.toMillis(5))(implicit scheduler: Scheduler): Unit = {
      scheduler.scheduleOnce(refreshFrequency)({
        //val timestampAtStart = new Date()
        val modifiedBefore: Date = new Date(modifiedAfter.getTime + searchWindowSize)
        fetchChanged(
          params = SearchParams(muuttunutJälkeen = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm").format(modifiedAfter),
                                muuttunutEnnen = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm").format(modifiedBefore))
        ).flatMap(fetchPersonAliases).onComplete {
          case Success((henkilot, personOidsWithAliases)) =>
            logger.info(s"muuttuneita henkilöitä (opiskeluoikeuksia): " + henkilot.size + " kpl")
            Try(triggerHenkilot(henkilot, personOidsWithAliases)) match {
              case Failure(e) => logger.error(e, "Exception in trigger!")
              case _ =>
            }
            processModifiedKoski(modifiedBefore, 5.minutes)
          case Failure(t) =>
            logger.error(t, "Fetching modified henkilot failed, retrying")
            processModifiedKoski(modifiedAfter, refreshFrequency)
        }
      })
  }

  private def triggerHenkilot(henkilot: Seq[KoskiHenkiloContainer], personOidsWithAliases: PersonOidsWithAliases) =
    henkilot.foreach(henkilo => {
      triggers.foreach( trigger => trigger.f(henkilo, personOidsWithAliases))
    })

  def addTrigger(trigger: KoskiTrigger) = triggers = triggers :+ trigger
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
