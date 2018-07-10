package fi.vm.sade.hakurekisteri.integration.koski

import java.text.SimpleDateFormat
import java.util.concurrent.TimeUnit
import java.util.{Date, TimeZone}

import akka.actor.{ActorSystem, Scheduler}
import akka.event.Logging
import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient
import fi.vm.sade.hakurekisteri.integration.hakemus.IHakemusService
import fi.vm.sade.hakurekisteri.integration.henkilo.{IOppijaNumeroRekisteri, PersonOidsWithAliases}
import org.joda.time.{DateTime, DateTimeZone}

import scala.compat.Platform
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.{FiniteDuration, _}
import scala.util.{Failure, Success, Try}


trait KoskiTriggerable {
  def trigger(a: KoskiHenkiloContainer, b: PersonOidsWithAliases)
}

case class KoskiTrigger(f: (KoskiHenkiloContainer, PersonOidsWithAliases, Boolean) => Unit)

class KoskiService(
                    virkailijaRestClient: VirkailijaRestClient,
                    oppijaNumeroRekisteri: IOppijaNumeroRekisteri,
                    hakemusService: IHakemusService, pageSize: Int = 200)(implicit val system: ActorSystem)  extends IKoskiService {

  private val HelsinkiTimeZone = TimeZone.getTimeZone("Europe/Helsinki")
  private val endDateSuomiTime = DateTime.parse("2018-06-05T18:00:00").withZoneRetainFields(DateTimeZone.forTimeZone(HelsinkiTimeZone))
  private val logger = Logging.getLogger(system, this)

  val fetchPersonAliases: (Seq[KoskiHenkiloContainer]) => Future[(Seq[KoskiHenkiloContainer], PersonOidsWithAliases)] = { hs: Seq[KoskiHenkiloContainer] =>
    logger.debug(s"Haetaan aliakset henkilöille=$hs")
    val personOids: Seq[String] = hs.flatMap(_.henkilö.oid)
    oppijaNumeroRekisteri.enrichWithAliases(personOids.toSet).map((hs, _))
  }

  case class SearchParams(muuttunutJälkeen: String, muuttunutEnnen: String = "2100-01-01T12:00")
  case class SearchParamsWithPagination (muuttunutJälkeen: String, muuttunutEnnen: String = "2100-01-01T12:00", pageSize: Int, pageNumber: Int)

  def fetchChanged(page: Int = 0, params: SearchParams): Future[Seq[KoskiHenkiloContainer]] = {
    virkailijaRestClient.readObjectWithBasicAuth[List[KoskiHenkiloContainer]]("koski.oppija", params)(acceptedResponseCode = 200, maxRetries = 2)
  }

  def fetchChangedWithPagination(page: Int = 0, params: SearchParamsWithPagination): Future[Seq[KoskiHenkiloContainer]] = {
    logger.debug(s"Haetaan henkilöt ja opiskeluoikeudet Koskesta, muuttuneet välillä: " + params.muuttunutJälkeen.toString + " - " + params.muuttunutEnnen.toString + ", sivu: " + params.pageNumber)
    virkailijaRestClient.readObjectWithBasicAuth[List[KoskiHenkiloContainer]]("koski.oppija", params)(acceptedResponseCode = 200, maxRetries = 2)
  }

  def clampTimeToEnd(date: Date): Date = {
    val dt = new DateTime(date)
    if (dt.isBefore(endDateSuomiTime)) {
      date
    } else {
      endDateSuomiTime.toDate
    }
  }

  //Tällä voi käydä läpi määritellyn aikaikkunan verran dataa Koskesta, jos joskus tulee tarve käsitellä aiempaa koskidataa uudelleen.
  //Oletusparametreilla hakee muutoset päivän taaksepäin, jotta Sure selviää alle 24 tunnin downtimeistä ilman Koskidatan puuttumista.
  override def traverseKoskiDataInChunks(searchWindowStartTime: Date = new Date(Platform.currentTime - TimeUnit.DAYS.toMillis(1)),
                                timeToWaitUntilNextBatch: FiniteDuration = 2.minutes,
                                searchWindowSize: Long = TimeUnit.DAYS.toMillis(10),
                                repairTargetTime: Date = new Date(Platform.currentTime),
                                pageNbr: Int = 0,
                                pageSizePerFetch: Int = 3000)(implicit scheduler: Scheduler): Unit = {
    if(searchWindowStartTime.getTime >= endDateSuomiTime.getMillis) {
      logger.info(s"HistoryCrawler - Törmättiin Koski-integraation sulkevaan aikaleimaan. " +
        s"Lopetetaan läpikäynti. Kaikki ennen aikaleimaa {} muuttunut data on käyty läpi.", endDateSuomiTime.toString)
      return
    }

    if(searchWindowStartTime.getTime < repairTargetTime.getTime) {
      scheduler.scheduleOnce(timeToWaitUntilNextBatch)({
        var searchWindowEndTime: Date = new Date(searchWindowStartTime.getTime + searchWindowSize)
        if (searchWindowEndTime.getTime > repairTargetTime.getTime) {
          searchWindowEndTime = new Date(repairTargetTime.getTime)
        }
        val clampedSearchWindowStartTime = clampTimeToEnd(searchWindowStartTime)
        searchWindowEndTime = clampTimeToEnd(searchWindowEndTime)
        fetchChangedWithPagination(
          params = SearchParamsWithPagination(muuttunutJälkeen = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm").format(clampedSearchWindowStartTime ),
            muuttunutEnnen = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm").format(searchWindowEndTime),
            pageSize = pageSizePerFetch,
            pageNumber = pageNbr)
        ).flatMap(fetchPersonAliases).onComplete {
          case Success((henkilot, personOidsWithAliases)) =>
            logger.info(s"HistoryCrawler - Aikaikkuna: " + clampedSearchWindowStartTime  + " - " + searchWindowEndTime + ", Sivu: " + pageNbr +" , Henkilöitä: " + henkilot.size + " kpl.")
            Try(triggerHenkilot(henkilot, personOidsWithAliases)) match {
              case Failure(e) => logger.error(e, "HistoryCrawler - Exception in trigger!")
              case _ =>
            }
            if(henkilot.isEmpty) {
              logger.info(s"HistoryCrawler - Siirrytään seuraavaan aikaikkunaan!")
              traverseKoskiDataInChunks(searchWindowEndTime, timeToWaitUntilNextBatch = 5.seconds, searchWindowSize, repairTargetTime, pageNbr = 0, pageSizePerFetch) //Koko aikaikkuna käsitelty, siirrytään seuraavaan
            } else {
              logger.info(s"HistoryCrawler - Haetaan saman aikaikkunan seuraava sivu!")
              traverseKoskiDataInChunks(clampedSearchWindowStartTime , timeToWaitUntilNextBatch = 2.minutes, searchWindowSize, repairTargetTime, pageNbr + 1, pageSizePerFetch) //Seuraava sivu samaa aikaikkunaa
            }
          case Failure(t) =>
            logger.error(t, "HistoryCrawler - fetch data failed, retrying")
            traverseKoskiDataInChunks(clampedSearchWindowStartTime , timeToWaitUntilNextBatch = 2.minutes, searchWindowSize, repairTargetTime, pageNbr, pageSizePerFetch) //Sama sivu samasta aikaikkunasta
        }
      })} else {
      logger.info(s"HistoryCrawler - koko haluttu aikaikkuna käyty läpi, lopetetaan läpikäynti.")
    }
  }

  //Pitää kirjaa, koska päivitys on viimeksi käynnistetty. Tämän kevyen toteutuksen on tarkoitus suojata siltä, että operaatio käynnistetään tahattoman monta kertaa.
  //Käynnistetään päivitys vain, jos edellisestä käynnistyksestä on yli minimiaika.
  private var startTimestamp: Long = 0
  val timeoutAfter: Long = TimeUnit.HOURS.toMillis(5)
  private var oneJobAtATime = Future.successful({})
  override def updateHenkilotForHaku(hakuOid: String, createLukio: Boolean = false, overrideTimeCheck: Boolean = false, useBulk: Boolean = false): Future[Unit] = {
    def handleUpdate(personOidsSet: Set[String]): Future[Unit] = {
      val personOids: Seq[String] = personOidsSet.toSeq
      logger.info(s"Saatiin hakemuspalvelusta ${personOids.length} oppijanumeroa haulle $hakuOid")
      handleHenkiloUpdate(personOids, createLukio)
    }
    val now = System.currentTimeMillis()
    synchronized {
      val reallyOldAndStillRunning = (now - startTimestamp) > timeoutAfter
      if(oneJobAtATime.isCompleted || reallyOldAndStillRunning) {
        if(reallyOldAndStillRunning) {
          logger.error(s"${TimeUnit.HOURS.convert(now - startTimestamp,TimeUnit.MILLISECONDS)} tuntia vanha Koskikutsu oli vielä käynnissä!")
        }
        oneJobAtATime = hakemusService.personOidsForHaku(hakuOid, None).flatMap(handleUpdate)
        startTimestamp = System.currentTimeMillis()
        Future.successful({})
      } else {
        val err = s"${TimeUnit.MINUTES.convert(now - startTimestamp,TimeUnit.MILLISECONDS)} minuuttia vanha Koskikutsu on vielä käynnissä!"
        logger.error(err)
        Future.failed(new RuntimeException(err))
      }
    }
  }

  def handleHenkiloUpdate(personOids: Seq[String], createLukio: Boolean): Future[Unit] = {
    val batchSize: Int = 1000
    val groupedOids: Seq[Seq[String]] = personOids.grouped(batchSize).toSeq
    val totalGroups: Int = groupedOids.length
    logger.info(s"HandleHenkiloUpdate: yhteensä $totalGroups kappaletta $batchSize kokoisia ryhmiä.")

    def handleBatch(batches: Seq[(Seq[String], Int)]): Future[Unit] = {
      batches match {
        case Nil => Future.successful({})
        case batch :: tail => {
          val (subSeq, index) = batch
          logger.info(s"HandleHenkiloUpdate: Päivitetään Koskesta $batchSize henkilöä sureen. Erä $index / $totalGroups")
          updateHenkilot(subSeq.toSet, createLukio).flatMap(s => handleBatch(tail))
        }
      }
    }

    handleBatch(groupedOids.zipWithIndex)
  }

  override def updateHenkilot(oppijaOids: Set[String], createLukio: Boolean = false, overrideTimeCheck: Boolean = false): Future[Unit] = {
    val oppijat: Future[Seq[KoskiHenkiloContainer]] = virkailijaRestClient
      .postObjectWithCodes[Set[String],Seq[KoskiHenkiloContainer]]("koski.sure", Seq(200), maxRetries = 2, resource = oppijaOids, basicAuth = true)
      .recoverWith {
        case e: Exception =>
          logger.error("Kutsu koskeen oppijanumeroille {} epäonnistui: {} ", oppijaOids, e)
          Future.failed(e)
      }

    val result: Future[Unit] = oppijat.flatMap(fetchPersonAliases).flatMap(res  => {
      val (henkilot, personOidsWithAliases) = res
      logger.info(s"Saatiin Koskesta ${henkilot.size} henkilöä!")
      Try(triggerHenkilot(henkilot, personOidsWithAliases, createLukio)) match {
        case Failure(exception) =>
          logger.error("Error triggering update for henkilö {} : {}", oppijaOids, exception)
          Future.failed(exception)
        case Success(value) => {
          logger.debug("updateHenkilo success")
          Future.successful(())
        }
      }
    })
    result
  }

  //Poistaa KoskiHenkiloContainerin sisältä sellaiset opiskeluoikeudet, joilla ei ole oppilaitosta jolla on määritelty oid.
  //Vaaditaan lisäksi, että käsiteltävillä opiskeluoikeuksilla on ainakin yksi tilatieto.
  private def removeOpiskeluoikeudesWithoutDefinedOppilaitosAndOppilaitosOids(data: Seq[KoskiHenkiloContainer]): Seq[KoskiHenkiloContainer] = {
    data.flatMap(container => {
      val oikeudet = container.opiskeluoikeudet.filter(oikeus => {
        oikeus.oppilaitos.isDefined && oikeus.oppilaitos.get.oid.isDefined && oikeus.tila.opiskeluoikeusjaksot.nonEmpty
      })
      if(oikeudet.nonEmpty) Seq(container.copy(opiskeluoikeudet = oikeudet)) else Seq()
    })
  }

  private def triggerHenkilot(henkilot: Seq[KoskiHenkiloContainer], personOidsWithAliases: PersonOidsWithAliases, createLukio: Boolean = false): Unit = {
    val filteredHenkilot = removeOpiskeluoikeudesWithoutDefinedOppilaitosAndOppilaitosOids(henkilot)
    if(filteredHenkilot.nonEmpty) {
      filteredHenkilot.foreach(henkilo => {
        triggers.foreach(trigger => trigger.f(henkilo, personOidsWithAliases, createLukio))
      })
    } else {
      logger.debug("Ei triggeröidä mitään, koska Container-kokoelma on tyhjä.")
    }
  }
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
                 oppilaitos: Option[KoskiOrganisaatio],
                 tila: KoskiOpiskeluoikeusjakso,
                 päättymispäivä: Option[String],
                 lisätiedot: Option[KoskiLisatiedot],
                 suoritukset: Seq[KoskiSuoritus],
                 tyyppi: Option[KoskiKoodi],
                 aikaleima: Option[String])

case class KoskiOpiskeluoikeusjakso(opiskeluoikeusjaksot: Seq[KoskiTila])

case class KoskiTila(alku: String, tila:KoskiKoodi)

case class KoskiOrganisaatio(oid: Option[String])

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
                  tutkintonimike: Seq[KoskiKoodi] = Nil,
                  tila: Option[KoskiKoodi] = None)

case class KoskiOsasuoritus(
                 koulutusmoduuli: KoskiKoulutusmoduuli,
                 tyyppi: KoskiKoodi,
                 arviointi: Seq[KoskiArviointi],
                 pakollinen: Option[Boolean],
                 yksilöllistettyOppimäärä: Option[Boolean],
                 osasuoritukset: Option[Seq[KoskiOsasuoritus]]
             )

case class KoskiArviointi(arvosana: KoskiKoodi, hyväksytty: Option[Boolean], päivä: Option[String])

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
