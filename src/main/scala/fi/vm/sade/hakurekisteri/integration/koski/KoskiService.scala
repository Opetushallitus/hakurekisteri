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


trait KoskiTriggerable {
  def trigger(a: KoskiHenkiloContainer, b: PersonOidsWithAliases)
}

case class KoskiTrigger(f: (KoskiHenkiloContainer, PersonOidsWithAliases, Boolean) => Unit)

class KoskiService(virkailijaRestClient: VirkailijaRestClient, oppijaNumeroRekisteri: IOppijaNumeroRekisteri, pageSize: Int = 200)(implicit val system: ActorSystem)  extends IKoskiService {

  val fetchPersonAliases: (Seq[KoskiHenkiloContainer]) => Future[(Seq[KoskiHenkiloContainer], PersonOidsWithAliases)] = { hs: Seq[KoskiHenkiloContainer] =>
    logger.debug(s"Haetaan aliakset henkilöille=$hs")
    val personOids: Seq[String] = hs.flatMap(_.henkilö.oid)
    oppijaNumeroRekisteri.enrichWithAliases(personOids.toSet).map((hs, _))
  }

  private val logger = Logging.getLogger(system, this)

  case class SearchParams(muuttunutJälkeen: String, muuttunutEnnen: String = "2100-01-01T12:00")
  case class SearchParamsWithPagination (muuttunutJälkeen: String, muuttunutEnnen: String = "2100-01-01T12:00", pageSize: Int, pageNumber: Int)

  def fetchChanged(page: Int = 0, params: SearchParams): Future[Seq[KoskiHenkiloContainer]] = {
    //logger.info(s"Haetaan henkilöt ja opiskeluoikeudet Koskesta, muuttuneet välillä: " + params.muuttunutJälkeen.toString + " - " + params.muuttunutEnnen.toString)
    virkailijaRestClient.readObjectWithBasicAuth[List[KoskiHenkiloContainer]]("koski.oppija", params)(acceptedResponseCode = 200, maxRetries = 2)
  }

  def fetchChangedWithPagination(page: Int = 0, params: SearchParamsWithPagination): Future[Seq[KoskiHenkiloContainer]] = {
    logger.info(s"Haetaan henkilöt ja opiskeluoikeudet Koskesta, muuttuneet välillä: " + params.muuttunutJälkeen.toString + " - " + params.muuttunutEnnen.toString + ", sivu: " + params.pageNumber)
    virkailijaRestClient.readObjectWithBasicAuth[List[KoskiHenkiloContainer]]("koski.oppija", params)(acceptedResponseCode = 200, maxRetries = 2)
  }

  //Tällä voi käydä läpi määritellyn aikaikkunan verran dataa Koskesta, jos joskus tulee tarve käsitellä aiempaa koskidataa uudelleen.
  //Oletusparametreilla hakee muutoset päivän taaksepäin, jotta Sure selviää alle 24 tunnin downtimeistä ilman Koskidatan puuttumista.
  override def traverseKoskiDataInChunks(searchWindowStartTime: Date = new Date(Platform.currentTime - TimeUnit.DAYS.toMillis(1)),
                                timeToWaitUntilNextBatch: FiniteDuration = 2.minutes,
                                searchWindowSize: Long = TimeUnit.DAYS.toMillis(15),
                                repairTargetTime: Date = new Date(Platform.currentTime),
                                pageNbr: Int = 0,
                                pageSizePerFetch: Int = 1500)(implicit scheduler: Scheduler): Unit = {
    if(searchWindowStartTime.getTime < repairTargetTime.getTime) {
      scheduler.scheduleOnce(timeToWaitUntilNextBatch)({
        var searchWindowEndTime: Date = new Date(searchWindowStartTime.getTime + searchWindowSize)
        if (searchWindowEndTime.getTime > repairTargetTime.getTime) {
          searchWindowEndTime = new Date(repairTargetTime.getTime)
        }
        fetchChangedWithPagination(
          params = SearchParamsWithPagination(muuttunutJälkeen = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm").format(searchWindowStartTime),
            muuttunutEnnen = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm").format(searchWindowEndTime),
            pageSize = pageSizePerFetch,
            pageNumber = pageNbr)
        ).flatMap(fetchPersonAliases).onComplete {
          case Success((henkilot, personOidsWithAliases)) =>
            logger.info(s"HistoryCrawler - Aikaikkuna: " + searchWindowStartTime + " - " + searchWindowEndTime + ", Sivu: " + pageNbr +" , Henkilöitä: " + henkilot.size + " kpl.")
            Try(triggerHenkilot(henkilot, personOidsWithAliases)) match {
              case Failure(e) => logger.error(e, "HistoryCrawler - Exception in trigger!")
              case _ =>
            }
            if(henkilot.isEmpty) {
              logger.info(s"HistoryCrawler - Siirrytään seuraavaan aikaikkunaan!")
              traverseKoskiDataInChunks(searchWindowEndTime, timeToWaitUntilNextBatch = 5.seconds, searchWindowSize, repairTargetTime, pageNbr = 0, pageSizePerFetch) //Koko aikaikkuna käsitelty, siirrytään seuraavaan
            } else {
              logger.info(s"HistoryCrawler - Haetaan saman aikaikkunan seuraava sivu!")
              traverseKoskiDataInChunks(searchWindowStartTime, timeToWaitUntilNextBatch = 2.minutes, searchWindowSize, repairTargetTime, pageNbr + 1, pageSizePerFetch) //Seuraava sivu samaa aikaikkunaa
            }
          case Failure(t) =>
            logger.error(t, "HistoryCrawler - fetch data failed, retrying")
            traverseKoskiDataInChunks(searchWindowStartTime, timeToWaitUntilNextBatch = 2.minutes, searchWindowSize, repairTargetTime, pageNbr, pageSizePerFetch) //Sama sivu samasta aikaikkunasta
        }
      })} else {
      logger.info(s"HistoryCrawler - koko haluttu aikaikkuna käyty läpi, lopetetaan läpikäynti.")
    }
  }

  //Päivitetään minuutin välein minuutin aikaikkunallinen muuttunutta dataa Koskesta.
  //HUOM: viive tietojen päivittymiselle koski -> sure runsaat 5 minuuttia oletusparametreilla.
  //On tärkeää laahata hieman menneisyydessä, koska hyvin lähellä nykyhetkeä saattaa jäädä tietoa siirtymättä Sureen
  //jos Kosken päässä data ei ole ehtinyt kantaan asti ennen kuin sen perään kysellään.
  var maximumCatchup: Long = TimeUnit.SECONDS.toMillis(30)
  def processModifiedKoski(searchWindowStartTime: Date = new Date(Platform.currentTime - TimeUnit.MINUTES.toMillis(5)),
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

  override def updateHenkilo(oppijaOid: String, createLukio: Boolean = false): Future[Unit] = {
    if (!createLukio) {
      logger.info(s"Haetaan henkilö ja opiskeluoikeudet Koskesta oidille " + oppijaOid)
    } else {
      logger.info(s"Haetaan henkilö ja opiskeluoikeudet sekä luodaan lukion suoritus arvosanoineen Koskesta oidille " + oppijaOid)
    }

    val oppijadatasingle: Future[KoskiHenkiloContainer] = virkailijaRestClient
      .readObjectWithBasicAuth[KoskiHenkiloContainer]("koski.oppija.oid", oppijaOid)(acceptedResponseCode = 200, maxRetries = 2)
      .recoverWith {
        case e: Exception =>
          logger.error("Kutsu koskeen epäonnistui", e)
          Future.failed(e)
      }

    val oppijadata = oppijadatasingle.map(container => List(container))
      .recoverWith {
      case e: Exception =>
        logger.error("Error", e)
        return Future.failed(e)
    }

    val result: Future[Unit] = oppijadata.flatMap(fetchPersonAliases).flatMap(res  => {
      val (henkilot, personOidsWithAliases) = res
      logger.debug(s"Haettu henkilöt=$henkilot")
      Try(triggerHenkilot(henkilot, personOidsWithAliases, createLukio)) match {
        case Failure(exception) =>
          logger.error("Error triggering update for henkilö", exception)
          Future.failed(exception)
        case Success(value) => {
          logger.debug("updateHenkilo success")
          Future.successful(())
        }
      }
    })
    result
  }

  private def triggerHenkilot(henkilot: Seq[KoskiHenkiloContainer], personOidsWithAliases: PersonOidsWithAliases, createLukio: Boolean = false): Unit =
    henkilot.foreach(henkilo => {
      triggers.foreach( trigger => trigger.f(henkilo, personOidsWithAliases, createLukio))
    })

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
                 oid: Option[String], //LUVA data does not have an OID
                 oppilaitos: KoskiOrganisaatio,
                 tila: KoskiOpiskeluoikeusjakso,
                 päättymispäivä: Option[String],
                 lisätiedot: Option[KoskiLisatiedot],
                 suoritukset: Seq[KoskiSuoritus],
                 tyyppi: Option[KoskiKoodi])

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
                  alkamispäivä: Option[String],
                  //jääLuokalle is only used for peruskoulu
                  jääLuokalle: Option[Boolean],
                  tila: Option[KoskiKoodi] = None)

case class KoskiOsasuoritus(
                 koulutusmoduuli: KoskiKoulutusmoduuli,
                 tyyppi: KoskiKoodi,
                 arviointi: Seq[KoskiArviointi],
                 pakollinen: Option[Boolean],
                 yksilöllistettyOppimäärä: Option[Boolean],
                 osasuoritukset: Option[Seq[KoskiOsasuoritus]]
             )

case class KoskiArviointi(arvosana: KoskiKoodi, hyväksytty: Option[Boolean])

case class KoskiKoulutusmoduuli(tunniste: Option[KoskiKoodi],
                                kieli: Option[KoskiKieli],
                                koulutustyyppi:
                                Option[KoskiKoodi],
                                laajuus: Option[KoskiValmaLaajuus],
                                pakollinen: Option[Boolean])

case class KoskiValmaLaajuus(arvo: Option[BigDecimal], yksikkö: KoskiKoodi)

case class KoskiKoodi(koodiarvo: String, koodistoUri: String)

case class KoskiVahvistus(päivä: String, myöntäjäOrganisaatio: KoskiOrganisaatio)

case class KoskiKieli(koodiarvo: String, koodistoUri: String)

case class KoskiLisatiedot(
                            erityisenTuenPäätös: Option[KoskiErityisenTuenPaatos],
                            vuosiluokkiinSitoutumatonOpetus: Option[Boolean])

case class KoskiErityisenTuenPaatos(opiskeleeToimintaAlueittain: Option[Boolean])
