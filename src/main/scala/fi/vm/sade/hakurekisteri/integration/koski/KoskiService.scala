package fi.vm.sade.hakurekisteri.integration.koski

import java.text.SimpleDateFormat
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.{Calendar, Date, TimeZone}

import akka.actor.{ActorSystem, Scheduler}
import akka.event.Logging
import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient
import fi.vm.sade.hakurekisteri.integration.hakemus.IHakemusService
import fi.vm.sade.hakurekisteri.integration.henkilo.{IOppijaNumeroRekisteri, PersonOidsWithAliases}
import fi.vm.sade.hakurekisteri.integration.koski.KoskiConstants.{KOLMEKYMMENTÄ, ZERO}
import org.joda.time.{DateTime, DateTimeZone}

import scala.compat.Platform
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.{FiniteDuration, _}
import scala.math.BigDecimal
import scala.util.{Failure, Success, Try}

class KoskiService(virkailijaRestClient: VirkailijaRestClient,
                   oppijaNumeroRekisteri: IOppijaNumeroRekisteri,
                   hakemusService: IHakemusService,
                   koskiArvosanaHandler: KoskiArvosanaHandler,
                   pageSize: Int = 200)(implicit val system: ActorSystem)  extends IKoskiService {

  private val HelsinkiTimeZone = TimeZone.getTimeZone("Europe/Helsinki")
  private val endDateSuomiTime = DateTime.parse("2018-06-05T18:00:00").withZoneRetainFields(DateTimeZone.forTimeZone(HelsinkiTimeZone))
  private val logger = Logging.getLogger(system, this)

  val aktiiviset2AsteYhteisHakuOidit = new AtomicReference[Set[String]](Set.empty)
  def setAktiiviset2AsteYhteisHaut(hakuOids: Set[String]): Unit = aktiiviset2AsteYhteisHakuOidit.set(hakuOids)

  val aktiivisetKKYhteisHakuOidit = new AtomicReference[Set[String]](Set.empty)
  def setAktiivisetKKYhteisHaut(hakuOids: Set[String]): Unit = aktiivisetKKYhteisHakuOidit.set(hakuOids)

  val fetchPersonAliases: (Seq[KoskiHenkiloContainer]) => Future[(Seq[KoskiHenkiloContainer], PersonOidsWithAliases)] = { hs: Seq[KoskiHenkiloContainer] =>
    logger.debug(s"Haetaan aliakset henkilöille=$hs")
    val personOids: Seq[String] = hs.flatMap(_.henkilö.oid)
    oppijaNumeroRekisteri.enrichWithAliases(personOids.toSet).map((hs, _))
  }

  case class SearchParams(muuttunutJälkeen: String, muuttunutEnnen: String = "2100-01-01T12:00")
  case class SearchParamsWithPagination (muuttunutJälkeen: String, muuttunutEnnen: String = "2100-01-01T12:00", pageSize: Int, pageNumber: Int)
  case class SearchParamsWithCursor(timestamp: Option[String], cursor: Option[String], pageSize: Int = 5000)

  def fetchChanged(page: Int = 0, params: SearchParams): Future[Seq[KoskiHenkiloContainer]] = {
    virkailijaRestClient.readObjectWithBasicAuth[List[KoskiHenkiloContainer]]("koski.oppija", params)(acceptedResponseCode = 200, maxRetries = 2)
  }

  def fetchChangedWithPagination(page: Int = 0, params: SearchParamsWithPagination): Future[Seq[KoskiHenkiloContainer]] = {
    logger.debug(s"Haetaan henkilöt ja opiskeluoikeudet Koskesta, muuttuneet välillä: " + params.muuttunutJälkeen.toString + " - " + params.muuttunutEnnen.toString + ", sivu: " + params.pageNumber)
    virkailijaRestClient.readObjectWithBasicAuth[List[KoskiHenkiloContainer]]("koski.oppija", params)(acceptedResponseCode = 200, maxRetries = 2)
  }

  def fetchChangedOppijas(params: SearchParamsWithCursor): Future[MuuttuneetOppijatResponse] = {
    logger.info(s"Haetaan muuttuneet henkilöoidit Koskesta, timestamp: " + params.timestamp.toString + ", cursor: " + params.cursor.toString)
    virkailijaRestClient.readObjectWithBasicAuth[MuuttuneetOppijatResponse]("koski.sure.muuttuneet-oppijat", params)(acceptedResponseCode = 200, maxRetries = 2)
  }

  def clampTimeToEnd(date: Date): Date = {
    val dt = new DateTime(date)
    if (dt.isBefore(endDateSuomiTime)) {
      date
    } else {
      endDateSuomiTime.toDate
    }
  }

  def refreshChangedOppijasFromKoski(cursor: Option[String] = None, timeToWaitUntilNextBatch: FiniteDuration = 1.minutes)(implicit scheduler: Scheduler): Unit = {
    val endDateSuomiTime = DateTime.parse("2019-06-05T18:00:00").withZoneRetainFields(DateTimeZone.forTimeZone(HelsinkiTimeZone))
    if(endDateSuomiTime.isBeforeNow) {
      logger.info("refreshChangedOppijasFromKoski : Cutoff date of {} reached, stopping.", endDateSuomiTime.toString)
    } else {
      scheduler.scheduleOnce(timeToWaitUntilNextBatch)({
        //Some("2018-06-01T00:00:00+02:00") kovakoodattu oletusaikaleima, tällä saadaan kaikki kesällä tapahtuneen koski-sure-päivitysten sulkemisen
        //jälkeen tulleet muutokset haettua. Tulee vähän ylimääräistäkin tietoa, mutta parempi erehtyä siihen suuntaan.
        val timestamp: Option[String] =
          if (!cursor.isDefined)
            //Some(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(new Date(Platform.currentTime - TimeUnit.DAYS.toMillis(360))))
            Some("2018-06-01T00:00:00+02:00")
          else None
        val params = SearchParamsWithCursor(timestamp, cursor)
        logger.info("refreshChangedOppijasFromKoski active, making call with params: {}", params)
        fetchChangedOppijas(params).onComplete {
          case Success(response: MuuttuneetOppijatResponse) =>
            logger.info("refreshChangedOppijasFromKoski : got {} muuttunees oppijas from Koski.", response.result.size)
            handleHenkiloUpdate(response.result, createLukio = false).onComplete {
              case Success(s) =>
                logger.info("refreshChangedOppijasFromKoski : batch handling success. Oppijas handled: {}", response.result.size)
                if (response.mayHaveMore)
                  refreshChangedOppijasFromKoski(Some(response.nextCursor), 1.minutes) //Haetaan nopeammin jos kaikkia tietoja samalla cursorilla ei vielä saatu
                else
                  refreshChangedOppijasFromKoski(Some(response.nextCursor), 5.minutes)
              case Failure(e) =>
                logger.error("refreshChangedOppijasFromKoski : Jokin meni vikaan muuttuneiden oppijoiden tietojen haussa: {}", e)
                refreshChangedOppijasFromKoski(cursor, 5.minutes)
            }
          case Failure(e) => logger.error("refreshChangedOppijasFromKoski : Jokin meni vikaan muuttuneiden oppijoiden selvittämisessä : {}", e)
            refreshChangedOppijasFromKoski(cursor, 5.minutes)
        }
      })
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
            saveKoskiHenkilotAsSuorituksetAndArvosanat(henkilot, personOidsWithAliases).onFailure {
              case e => logger.error(e, "HistoryCrawler - Exception in trigger!")
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

  //Päivitetään minuutin välein minuutin aikaikkunallinen muuttunutta dataa Koskesta.
  //HUOM: viive tietojen päivittymiselle koski -> sure runsaat 5 minuuttia oletusparametreilla.
  //On tärkeää laahata hieman menneisyydessä, koska hyvin lähellä nykyhetkeä saattaa jäädä tietoa siirtymättä Sureen
  //jos Kosken päässä data ei ole ehtinyt kantaan asti ennen kuin sen perään kysellään.
  var maximumCatchup: Long = TimeUnit.SECONDS.toMillis(30)
  def processModifiedKoski(searchWindowStartTime: Date = new Date(Platform.currentTime - TimeUnit.MINUTES.toMillis(5)),
                           refreshFrequency: FiniteDuration = 1.minute,
                           searchWindowSize: Long = TimeUnit.MINUTES.toMillis(1))(implicit scheduler: Scheduler): Unit = {
    if(endDateSuomiTime.isBeforeNow) {
      logger.info("processModifiedKoski - Cutoff date of {} reached, stopping", endDateSuomiTime.toString)
      return
    }
    scheduler.scheduleOnce(refreshFrequency)({
      var catchup = false //Estetään prosessoijaa jättäytymästä vähitellen yhä enemmän jälkeen vaihtelevien käsittelyaikojen takia
      var searchWindowEndTime: Date = new Date(searchWindowStartTime.getTime + searchWindowSize)
      if (searchWindowStartTime.getTime < (Platform.currentTime - TimeUnit.MINUTES.toMillis(5))) {
        searchWindowEndTime = new Date(searchWindowStartTime.getTime + searchWindowSize + maximumCatchup)
        catchup = true
      }
      val clampedSearchWindowStartTime = clampTimeToEnd(searchWindowStartTime)
      searchWindowEndTime = clampTimeToEnd(searchWindowEndTime)
      fetchChanged(
        params = SearchParams(muuttunutJälkeen = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm").format(clampedSearchWindowStartTime ),
          muuttunutEnnen = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm").format(searchWindowEndTime ))
      ).flatMap(fetchPersonAliases).onComplete {
        case Success((henkilot, personOidsWithAliases)) =>
          logger.info(s"processModifiedKoski - muuttuneita opiskeluoikeuksia aikavälillä " + clampedSearchWindowStartTime  + " - " + searchWindowEndTime  + ": " + henkilot.size + " kpl. Catchup " + catchup.toString)
          saveKoskiHenkilotAsSuorituksetAndArvosanat(henkilot, personOidsWithAliases).onFailure {
            case e => logger.error(e, "processModifiedKoski - Exception in trigger!")
          }
          processModifiedKoski(searchWindowEndTime, refreshFrequency)
        case Failure(t) =>
          logger.error(t, "processModifiedKoski - fetching modified henkilot failed, retrying")
          processModifiedKoski(clampedSearchWindowStartTime , refreshFrequency)
      }
    })
  }

  /*
    *OK-227 : haun automatisointi.
    * Hakee joka yö:
    * - Aktiivisten 2. asteen hakujen lukiosuoritukset Koskesta
    * - Aktiivisten korkeakouluhakujen ammatilliset suoritukset Koskesta
    */
  override def updateAktiivisetHaut(): () => Unit = { () =>
    var haut: Set[String] = aktiiviset2AsteYhteisHakuOidit.get()
    logger.info(("Saatiin hakemuspalvelusta toisen asteen aktiivisia hakuja " + haut.size + " kpl, aloitetaan lukiosuoritusten päivitys."))
    haut.foreach(haku => {
      logger.info(s"Käynnistetään Koskesta lukiosuoritusten ajastettu päivitys haulle ${haku}")
      Await.result(updateHenkilotForHaku(haku, true), 5.hours)
    })
    logger.info(("Lukioarvosanojen päivitys valmis."))

    haut = aktiivisetKKYhteisHakuOidit.get()
    logger.info(("Saatiin hakemuspalvelusta aktiivisia korkeakoulujen hakuja " + haut.size + " kpl, aloitetaan ammatillisten suoritusten päivitys."))
    haut.foreach(haku => {
      logger.info(s"Käynnistetään Koskesta ammatillisten suoritusten ajastettu päivitys haulle ${haku}")
      Await.result(updateHenkilotForHaku(haku, false), 5.hours)
    })
    logger.info(("Korkeakouluhakujen ammatillisten suoritusten päivitys valmis."))
  }

  //Pitää kirjaa, koska päivitys on viimeksi käynnistetty. Tämän kevyen toteutuksen on tarkoitus suojata siltä, että operaatio käynnistetään tahattoman monta kertaa.
  //Käynnistetään päivitys vain, jos edellisestä käynnistyksestä on yli minimiaika.
  private var startTimestamp: Long = 0L
  val timeoutAfter: Long = TimeUnit.HOURS.toMillis(5)
  private var oneJobAtATime = Future.successful({})
  override def updateHenkilotForHaku(hakuOid: String, createLukio: Boolean = false): Future[Unit] = {
    def handleUpdate(personOidsSet: Set[String]): Future[Unit] = {
      val personOids: Seq[String] = personOidsSet.toSeq
      logger.info(s"Saatiin hakemuspalvelusta ${personOids.length} oppijanumeroa haulle $hakuOid")
      handleHenkiloUpdate(personOids, createLukio)
    }
    val now = System.currentTimeMillis()
    synchronized {
      if(oneJobAtATime.isCompleted) {
        logger.info(s"Käynnistetään Koskesta päivittäminen haulle ${hakuOid}")
        startTimestamp = System.currentTimeMillis()
        //OK-227 : We'll have to wait that the onJobAtATime is REALLY done:
        oneJobAtATime = Await.ready(hakemusService.personOidsForHaku(hakuOid, None).flatMap(handleUpdate), 5.hours)
        Future.successful({})
      } else {
        val err = s"${TimeUnit.MINUTES.convert(now - startTimestamp,TimeUnit.MILLISECONDS)} minuuttia vanha Koskesta päivittäminen on vielä käynnissä!"
        logger.error(err)
        Future.failed(new RuntimeException(err))
      }
    }
  }

  def handleHenkiloUpdate(personOids: Seq[String], createLukio: Boolean): Future[Unit] = {
    logger.info("HandleHenkiloUpdate: {} oppijanumeros", personOids.size)
    val batchSize: Int = 1000
    val groupedOids: Seq[Seq[String]] = personOids.grouped(batchSize).toSeq
    val totalGroups: Int = groupedOids.length
    logger.info(s"HandleHenkiloUpdate: yhteensä $totalGroups kappaletta $batchSize kokoisia ryhmiä.")

    def handleBatch(batches: Seq[(Seq[String], Int)]): Future[Unit] = {
      if(batches.isEmpty) {
        Future.successful({})
      } else {
        val (subSeq, index) = batches.head
        logger.info(s"HandleHenkiloUpdate: Päivitetään Koskesta $batchSize henkilöä sureen. Erä $index / $totalGroups")
        updateHenkilot(subSeq.toSet, createLukio).flatMap(s => handleBatch(batches.tail))
      }
    }

    val f = handleBatch(groupedOids.zipWithIndex)
    f.onComplete {
      case Success(_) => logger.info("Koskipäivitys valmistui!")
      case Failure(e) => logger.error(s"Koskipäivitys epäonnistui: ${e.getMessage}")
    }
    f
  }

  override def updateHenkilot(oppijaOids: Set[String], createLukio: Boolean = false, overrideTimeCheck: Boolean = false): Future[Unit] = {
    val oppijat: Future[Seq[KoskiHenkiloContainer]] = virkailijaRestClient
      .postObjectWithCodes[Set[String],Seq[KoskiHenkiloContainer]]("koski.sure", Seq(200), maxRetries = 2, resource = oppijaOids, basicAuth = true)
      .recoverWith {
        case e: Exception =>
          logger.error("Kutsu koskeen oppijanumeroille {} epäonnistui: {} ", oppijaOids, e)
          Future.failed(e)
      }

    oppijat.flatMap(fetchPersonAliases).flatMap(res => {
      val (henkilot, personOidsWithAliases) = res
      logger.info(s"Saatiin Koskesta ${henkilot.size} henkilöä, aliakset haettu!")
      saveKoskiHenkilotAsSuorituksetAndArvosanat(henkilot, personOidsWithAliases, createLukio)
    })
  }

  //Poistaa KoskiHenkiloContainerin sisältä sellaiset opiskeluoikeudet, joilla ei ole oppilaitosta jolla on määritelty oid.
  //Vaaditaan lisäksi, että käsiteltävillä opiskeluoikeuksilla on ainakin yksi tilatieto.
  private def removeOpiskeluoikeudesWithoutDefinedOppilaitosAndOppilaitosOids(data: Seq[KoskiHenkiloContainer]): Seq[KoskiHenkiloContainer] = {
    data.flatMap(container => {
      val oikeudet = container.opiskeluoikeudet.filter(_.isStateContainingOpiskeluoikeus)
      if(oikeudet.nonEmpty) Seq(container.copy(opiskeluoikeudet = oikeudet)) else Seq()
    })
  }

  private def saveKoskiHenkilotAsSuorituksetAndArvosanat(henkilot: Seq[KoskiHenkiloContainer], personOidsWithAliases: PersonOidsWithAliases, createLukio: Boolean = false): Future[Unit] = {
    val filteredHenkilot = removeOpiskeluoikeudesWithoutDefinedOppilaitosAndOppilaitosOids(henkilot)
    if(filteredHenkilot.nonEmpty) {
      Future.sequence(filteredHenkilot.map(henkilo =>
        koskiArvosanaHandler.muodostaKoskiSuorituksetJaArvosanat(henkilo, personOidsWithAliases.intersect(henkilo.henkilö.oid.toSet), createLukio)
      )).flatMap(_ => Future.successful({})).recoverWith{
        case e: Exception =>
          logger.error("Koskisuoritusten tallennus henkilölle epäonnistui: {} " , e)
          Future.failed(e)
      }
    } else {
      logger.info("saveKoskiHenkilotAsSuorituksetAndArvosanat: henkilölistaus tyhjä.")
      Future.successful({})
    }
  }
}

case class MuuttuneetOppijatResponse(result: Seq[String], mayHaveMore: Boolean, nextCursor: String)

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
                 aikaleima: Option[String]) {

  def isStateContainingOpiskeluoikeus =
    oppilaitos.isDefined && oppilaitos.get.oid.isDefined && tila.opiskeluoikeusjaksot.nonEmpty
}

object KoskiConstants {
  val valinnaisetkielet = Set("A1", "B1")
  val a2b2Kielet = Set("A2", "B2")
  val valinnaiset = Set("KO") ++ valinnaisetkielet

  val kielet = Set("A1", "A12", "A2", "A22", "B1", "B2", "B22", "B23", "B3", "B32", "B33")
  val oppiaineet = Set( "HI", "MU", "BI", "KT", "FI", "KO", "KE", "YH", "TE", "KS", "FY", "GE", "LI", "KU", "MA")
  val eivalinnaiset = kielet ++ oppiaineet ++ Set("AI")
  val peruskoulunaineet = kielet ++ oppiaineet ++ Set("AI")
  val lukioaineet = peruskoulunaineet ++ Set("PS") //lukio has psychology as a mandatory subject
  val lukioaineetRegex = lukioaineet.map(_.r)

  val kieletRegex = kielet.map(str => str.r)
  val oppiaineetRegex = oppiaineet.map(str => s"$str\\d?".r)
  val peruskouluaineetRegex = kieletRegex ++ oppiaineetRegex ++ Set("AI".r)

  val peruskoulunArvosanat = Set[String]("4", "5", "6", "7", "8", "9", "10", "S")
  val aidinkieli = Map("AI1" -> "FI", "AI2" -> "SV", "AI3" -> "SE", "AI4" -> "RI", "AI5" -> "VK", "AI6" -> "XX", "AI7" -> "FI_2", "AI8" -> "SV_2", "AI9" -> "FI_SE", "AI10" -> "XX", "AI11" -> "FI_VK", "AI12" -> "SV_VK", "AIAI" -> "XX")

  val ZERO = BigDecimal("0")
  val KOLMEKYMMENTÄ = BigDecimal("30")
}

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
                  tila: Option[KoskiKoodi] = None) {

  def valmaOsaamispisteetAlleKolmekymmentä: Boolean = {
    val sum = osasuoritukset
      .filter(_.arviointi.exists(_.hyväksytty.contains(true)))
      .flatMap(_.koulutusmoduuli.laajuus)
      .map(_.arvo.getOrElse(BigDecimal(0)))
      .sum
    sum.<(KOLMEKYMMENTÄ)
  }

  def opintopisteidenMaaraAlleKolmekymmentä: Boolean = {
    val pisteet: Seq[BigDecimal] = osasuoritukset.map(_.opintopisteidenMaara)

    pisteet.sum.<(KOLMEKYMMENTÄ)
  }

}

case class KoskiOsasuoritus(
                 koulutusmoduuli: KoskiKoulutusmoduuli,
                 tyyppi: KoskiKoodi,
                 arviointi: Seq[KoskiArviointi],
                 pakollinen: Option[Boolean],
                 yksilöllistettyOppimäärä: Option[Boolean],
                 osasuoritukset: Option[Seq[KoskiOsasuoritus]]
             ) {

  def opintopisteidenMaara: BigDecimal = {
    val laajuus: Option[KoskiValmaLaajuus] = koulutusmoduuli.laajuus.filter(_.yksikkö.koodiarvo == "2")
    val arvo: Option[BigDecimal] = laajuus.flatMap(_.arvo)
    arvo.getOrElse(ZERO)
  }

  def isLukioSuoritus: Boolean = {

    koulutusmoduuli.tunniste.map(_.koodiarvo) match {
      case Some(koodi) =>
        KoskiConstants.lukioaineetRegex.flatMap(_.findFirstIn(koodi)).nonEmpty
      case _ => false
    }
  }

  def isPK: Boolean = {
    koulutusmoduuli.tunniste.map(_.koodiarvo) match {
      case Some(koodi) =>
        KoskiConstants.peruskouluaineetRegex.flatMap(_.findFirstIn(koodi)).nonEmpty
      case _ => false
    }
  }

}

case class KoskiArviointi(arvosana: KoskiKoodi, hyväksytty: Option[Boolean], päivä: Option[String]) {
  def isPKValue: Boolean = {
    KoskiConstants.peruskoulunArvosanat.contains(arvosana.koodiarvo) || arvosana.koodiarvo == "H"
  }
}

case class KoskiKoulutusmoduuli(tunniste: Option[KoskiKoodi],
                                kieli: Option[KoskiKieli],
                                koulutustyyppi:
                                Option[KoskiKoodi],
                                laajuus: Option[KoskiValmaLaajuus],
                                pakollinen: Option[Boolean])

case class KoskiValmaLaajuus(arvo: Option[BigDecimal], yksikkö: KoskiKoodi)

case class KoskiKoodi(koodiarvo: String, koodistoUri: String) {
  def valinnainen: Boolean = {
    KoskiConstants.valinnaiset.contains(koodiarvo)
  }
  def eivalinnainen: Boolean = {
    KoskiConstants.eivalinnaiset.contains(koodiarvo)
  }
  def a2b2Kielet: Boolean = {
    KoskiConstants.a2b2Kielet.contains(koodiarvo)
  }
  def kielet: Boolean = {
    KoskiConstants.kielet.contains(koodiarvo)
  }
}

case class KoskiVahvistus(päivä: String, myöntäjäOrganisaatio: KoskiOrganisaatio)

case class KoskiKieli(koodiarvo: String, koodistoUri: String)

case class KoskiLisatiedot(
                            erityisenTuenPäätös: Option[KoskiErityisenTuenPaatos],
                            vuosiluokkiinSitoutumatonOpetus: Option[Boolean])

case class KoskiErityisenTuenPaatos(opiskeleeToimintaAlueittain: Option[Boolean])
