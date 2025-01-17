package fi.vm.sade.hakurekisteri.integration.koski

import java.util.concurrent.{ConcurrentHashMap, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import java.util.TimeZone
import akka.actor.{ActorSystem, Scheduler}
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.{ExecutorUtil, VirkailijaRestClient}
import fi.vm.sade.hakurekisteri.integration.hakemus.IHakemusService
import fi.vm.sade.hakurekisteri.integration.henkilo.{
  Henkilo,
  IOppijaNumeroRekisteri,
  PersonOidsWithAliases
}
import org.joda.time.{DateTime, DateTimeZone}
import com.github.blemale.scaffeine.{Cache, Scaffeine}
import org.slf4j.{Logger, LoggerFactory}

import java.sql.Date
import java.text.SimpleDateFormat
import scala.concurrent.ExecutionContextExecutorService
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.{FiniteDuration, _}
import scala.util.{Failure, Success}

case class KoskiMassaluovutusQueryParams(
  `type`: String,
  format: String,
  oppijaOids: Option[Set[String]], //Käytännössä joko oppijaOids tai muuttuneetJälkeen on määritelty
  muuttuneetJälkeen: Option[String]
)
case class KoskiMassaluovutusQueryResponse(
  queryId: String,
  requestedBy: String,
  query: KoskiMassaluovutusQueryParams,
  createdAt: Option[String],
  startedAt: Option[String],
  finishedAt: Option[String],
  files: Seq[String],
  resultsUrl: Option[String],
  progress: Option[Map[String, String]],
  sourceDataUpdatedAt: Option[String],
  status: String
) {
  def isFinished() = status.equals("complete") || isFailed()
  def isFailed() = status.equals("failed")
}

case class KoskiProcessingResults(
  succeededHenkiloOids: Set[String],
  failedHenkiloOids: Set[String]
) {
  def getFailedStr() = {
    if (failedHenkiloOids.isEmpty) ""
    else
      s"Epäonnistuneet (${Math.min(failedHenkiloOids.size, 50)} ensimmäistä:) ${failedHenkiloOids
        .take(50)}"
  }
}

case class KoskiMassaluovutusFileResult(
  oppijaOid: String,
  kaikkiOidit: Seq[String],
  aikaleima: String,
  opiskeluoikeudet: Seq[KoskiOpiskeluoikeus]
)

class KoskiService(
  virkailijaRestClient: VirkailijaRestClient,
  oppijaNumeroRekisteri: IOppijaNumeroRekisteri,
  hakemusService: IHakemusService,
  koskiDataHandler: KoskiDataHandler,
  config: Config,
  pageSize: Int = 200
)(implicit val system: ActorSystem)
    extends IKoskiService {
  implicit val ec: ExecutionContextExecutorService =
    ExecutorUtil.createExecutor(8, "koski-sure-executor-pool")
  private val koskiKoulusivistyskieliCache: Cache[String, Seq[String]] =
    Scaffeine()
      .recordStats()
      .expireAfterWrite(12.hour)
      .maximumSize(50000)
      .build[String, Seq[String]]()

  private val HelsinkiTimeZone = TimeZone.getTimeZone("Europe/Helsinki")
  val logger: Logger = LoggerFactory.getLogger(getClass)

  private var startTimestamp: Long = 0L
  private var oneJobAtATime =
    Future.successful(KoskiProcessingResults(Set[String](), Set[String]()))

  val aktiiviset2AsteYhteisHakuOidit = new AtomicReference[Set[String]](Set.empty)
  def setAktiiviset2AsteYhteisHaut(hakuOids: Set[String]): Unit = {
    logger.info(s"Asetetaan aktiiviset toisen asteen yhteishaut: $hakuOids")
    aktiiviset2AsteYhteisHakuOidit.set(hakuOids)
  }

  val aktiivisetKKYhteisHakuOidit = new AtomicReference[Set[String]](Set.empty)
  def setAktiivisetKKYhteisHaut(hakuOids: Set[String]): Unit = {
    logger.info(s"Asetetaan aktiiviset KK-yhteishaut haut: $hakuOids")
    aktiivisetKKYhteisHakuOidit.set(hakuOids)
  }

  val aktiivisetKKHakuOidit = new AtomicReference[Set[String]](Set.empty)
  def setAktiivisetKKHaut(hakuOids: Set[String]): Unit = {
    logger.info(s"Asetetaan aktiiviset KK-yhteishaut haut: $hakuOids")
    aktiivisetKKHakuOidit.set(hakuOids)
  }

  val aktiivistenToisenAsteenJatkuvienHakujenOidit = new AtomicReference[Set[String]](Set.empty)
  def setAktiivisetToisenAsteenJatkuvatHaut(hakuOids: Set[String]): Unit = {
    logger.info(s"Asetetaan aktiiviset toisen asteen jatkuvat haut: $hakuOids")
    aktiivistenToisenAsteenJatkuvienHakujenOidit.set(hakuOids)
  }

  private val fetchPersonAliases
    : Seq[KoskiHenkiloContainer] => Future[(Seq[KoskiHenkiloContainer], PersonOidsWithAliases)] = {
    hs: Seq[KoskiHenkiloContainer] =>
      logger.info(s"Haetaan aliaksia henkilöille...")
      val personOids: Seq[String] = hs.flatMap(_.henkilö.oid)
      oppijaNumeroRekisteri.enrichWithAliases(personOids.toSet).map((hs, _))
  }

  override def fetchOppivelvollisuusTietos(
    oppijaOids: Seq[String]
  ): Future[Seq[OppivelvollisuusTieto]] = {
    logger.info(s"Haetaan oppivelvollisuustiedot koskesta")
    virkailijaRestClient.postObjectWithCodes[Seq[String], Seq[OppivelvollisuusTieto]](
      uriKey = "koski.sure.oppivelvollisuustieto",
      Seq(200),
      maxRetries = 2,
      resource = oppijaOids,
      basicAuth = true
    )
  }

  def refreshChangedOppijasFromKoski(
    lastQueryStart: Option[String],
    timeToWaitUntilNextBatch: FiniteDuration = 10.seconds
  )(implicit scheduler: Scheduler): Unit = {
    logger.info(s"refreshChangedOppijasFromKoski called!")
    val endDateSuomiTime =
      KoskiUtil.deadlineDate
        .plusDays(1)
        .toDateTimeAtStartOfDay(DateTimeZone.forTimeZone(HelsinkiTimeZone))
    if (endDateSuomiTime.isBeforeNow) {
      logger.info(
        "refreshChangedOppijasFromKoski : Cutoff date of {} reached, stopping.",
        endDateSuomiTime.toString
      )
    } else {
      scheduler.scheduleOnce(timeToWaitUntilNextBatch)({
        //vvvv-kk-ppThh:mm:ss
        val muuttunutJalkeenStr = lastQueryStart.getOrElse(KoskiUtil.koskiFetchStartTime)
        logger.info(
          s"refreshChangedOppijasFromKoski käynnistyy! Käsitellään muuttuneet $muuttunutJalkeenStr"
        )
        val thisQueryStart = new SimpleDateFormat(
          "yyyy-MM-dd'T'HH:mm:ss"
        ) //tästä tulee seuraavan iteraation aloitushetki
          .format(new Date(System.currentTimeMillis()))
        logger.info(
          s"refreshChangedOppijasFromKoski : nyt $thisQueryStart, haetaan muuttuneet jälkeen: $muuttunutJalkeenStr"
        )
        val koskiParams = KoskiSuoritusTallennusParams(saveLukio = false, saveAmmatillinen = false)
        val resultF = handleKoskiRefreshMuuttunutJalkeen(muuttunutJalkeenStr, koskiParams)

        resultF.onComplete {
          case Success(processingResults) =>
            logger.info(
              s"refreshChangedOppijasFromKoski : saatiin käsiteltyä muutokset alkaen $muuttunutJalkeenStr. Onnistuneita ${processingResults.succeededHenkiloOids.size}, epäonnistuneita ${processingResults.failedHenkiloOids.size}. ${processingResults
                .getFailedStr()}"
            )
            refreshChangedOppijasFromKoski(Some(thisQueryStart), 5.minutes)
          case Failure(f) =>
            logger.error(
              s"refreshChangedOppijasFromKoski : jotain meni vikaan muutosten käsittelyssä. Yritetään myöhemmin uudelleen samoilla parametreilla. ${f.getMessage}",
              f
            )
            refreshChangedOppijasFromKoski(Some(muuttunutJalkeenStr), 1.minute)
        }
      })
    }
  }

  /*
   *OK-227 : haun automatisointi.
   * Hakee joka yö:
   * - Aktiivisten korkeakouluhakujen ammatilliset suoritukset Koskesta
   */
  override def updateAktiivisetKkAsteenHaut(): () => Unit = { () =>
    val haut: Set[String] = aktiivisetKKHakuOidit.get()
    logger.info(
      s"Saatiin tarjonnasta aktiivisia korkeakoulujen hakuja ${haut.size} kpl, aloitetaan ammatillisten suoritusten päivitys."
    )
    haut.foreach(haku => {
      logger.info(
        s"Käynnistetään Koskesta aktiivisten korkeakouluhakujen ammatillisten suoritusten ajastettu päivitys haulle ${haku}"
      )
      Await.result(
        syncHaunHakijat(
          haku,
          KoskiSuoritusTallennusParams(saveLukio = false, saveAmmatillinen = true)
        ),
        5.hours
      )
    })
    logger.info("Aktiivisten korkeakoulu-yhteishakujen ammatillisten suoritusten päivitys valmis.")
  }

  /*
   *OK-227 : haun automatisointi.
   * Hakee joka yö:
   * - Aktiivisten 2. asteen hakujen lukiosuoritukset Koskesta
   */
  override def updateAktiivisetToisenAsteenHaut(): () => Unit = { () =>
    val haut: Set[String] = aktiiviset2AsteYhteisHakuOidit.get()
    logger.info(
      s"Saatiin tarjonnasta toisen asteen aktiivisia hakuja ${haut.size} kpl, aloitetaan lukiosuoritusten päivitys."
    )
    haut.foreach(haku => {
      logger.info(
        s"Käynnistetään Koskesta aktiivisten toisen asteen hakujen lukiosuoritusten ajastettu päivitys haulle ${haku}"
      )
      Await.result(
        syncHaunHakijat(
          haku,
          KoskiSuoritusTallennusParams(
            saveLukio = true,
            saveAmmatillinen = false,
            saveSeiskaKasiJaValmistava = true
          )
        ),
        5.hours
      )
    })
    logger.info("Aktiivisten toisen asteen yhteishakujen lukiosuoritusten päivitys valmis.")
  }

  override def updateAktiivisetToisenAsteenJatkuvatHaut(): () => Unit = { () =>
    val hakuOids: Set[String] = aktiivistenToisenAsteenJatkuvienHakujenOidit.get()
    logger.info(
      s"Saatiin tarjonnasta jatkuvia hakuja ${hakuOids.size} kpl: ${hakuOids}, aloitetaan päivitys."
    )
    hakuOids.foreach(hakuOid => {
      logger.info(
        s"Käynnistetään Koskesta ajastettu päivitys jatkuvalle haulle ${hakuOid}"
      )
      Await.result(
        syncHaunHakijat(
          hakuOid,
          KoskiSuoritusTallennusParams(
            saveLukio = true,
            saveAmmatillinen = false,
            saveSeiskaKasiJaValmistava = true
          ),
          hakuOid => hakemusService.springPersonOidsForJatkuvaHaku(hakuOid)
        ),
        5.hours
      )
    })
    logger.info("Aktiivisten toisen asteen jatkuvien hakujen hakijoiden päivitys valmis.")
  }

  override def syncHaunHakijat(
    hakuOid: String,
    params: KoskiSuoritusTallennusParams,
    personOidsForHakuFn: String => Future[Set[String]]
  ) = {
    def handleUpdate(personOidsSet: Set[String]): Future[KoskiProcessingResults] = {
      val personOidsWithAliases: PersonOidsWithAliases = Await.result(
        oppijaNumeroRekisteri.enrichWithAliases(personOidsSet),
        Duration(1, TimeUnit.MINUTES)
      )
      val aliasCount: Int =
        personOidsWithAliases.henkiloOidsWithLinkedOids.size - personOidsSet.size
      logger.info(
        s"Saatiin hakemuspalvelusta ${personOidsSet.size} oppijanumeroa ja ${aliasCount} aliasta haulle $hakuOid"
      )
      handleKoskiRefreshForOppijaOids(personOidsWithAliases.henkiloOidsWithLinkedOids, params)
    }

    val now = System.currentTimeMillis()
    synchronized {
      if (oneJobAtATime.isCompleted) {
        logger.info(s"Käynnistetään Koskesta päivittäminen haulle ${hakuOid}. Params: ${params}")
        startTimestamp = System.currentTimeMillis()
        //OK-227 : We'll have to wait that the onJobAtATime is REALLY done:
        oneJobAtATime = Await.ready(
          personOidsForHakuFn(hakuOid).flatMap(handleUpdate),
          5.hours
        )
        logger.info(s"Päivitys Koskesta haulle ${hakuOid} valmistui.")
        Future.successful({})
      } else {
        val err =
          s"${TimeUnit.MINUTES.convert(now - startTimestamp, TimeUnit.MILLISECONDS)} minuuttia vanha Koskesta päivittäminen on vielä käynnissä!"
        logger.error(err)
        Future.failed(new RuntimeException(err))
      }
    }
  }

  override def syncHaunHakijat(hakuOid: String, params: KoskiSuoritusTallennusParams) = {
    syncHaunHakijat(hakuOid, params, hakuOid => hakemusService.personOidsForHaku(hakuOid, None))
  }

  override def updateHenkilotWithAliases(
    oppijaOids: Set[String],
    params: KoskiSuoritusTallennusParams
  ): Future[(Seq[String], Seq[String])] = {
    logger.info(s"Haetaan oppijanumerorekisteristä aliakset oppijanumeroille: $oppijaOids")
    val personOidsWithAliases: PersonOidsWithAliases = Await.result(
      oppijaNumeroRekisteri.enrichWithAliases(oppijaOids),
      Duration(1, TimeUnit.MINUTES)
    )
    val aliasCount: Int = personOidsWithAliases.henkiloOidsWithLinkedOids.size - oppijaOids.size
    logger.info(
      s"Yhteensä ${personOidsWithAliases.henkiloOidsWithLinkedOids.size} oppijanumeroa joista aliaksia ${aliasCount} kpl."
    )
    updateHenkilot(personOidsWithAliases.henkiloOidsWithLinkedOids, params)
  }

  def fetchHenkilot(
    oppijaOids: Set[String]
  ): Future[Seq[KoskiHenkiloContainer]] = {
    virkailijaRestClient
      .postObjectWithCodes[Set[String], Seq[KoskiHenkiloContainer]](
        "koski.sure",
        Seq(200),
        maxRetries = 2,
        resource = oppijaOids,
        basicAuth = true
      )
      .recoverWith { case e: Exception =>
        logger.error(s"Kutsu koskeen oppijanumeroille $oppijaOids epäonnistui:", e)
        Future.failed(e)
      }
  }

  def koulusivistyskieletForAliases(koskiData: KoskiHenkiloContainer): Map[String, Seq[String]] = {
    val kaikkiOidit = (koskiData.henkilö.oid, koskiData.henkilö.kaikkiOidit) match {
      case (_, Some(kaikkiOidit)) =>
        kaikkiOidit //Todo, varmista että masterOid on kaikissa oideissa mukana. Mut eiköhän ole. Voi yksinkertaistaa päättelyä myös jos kaikkiOidit palautuvat aina.
      case (Some(oid), _) => Seq(oid)
      case _              => Seq.empty
    }
    val koulusivistyskieli = resolveKoulusivistyskieli(koskiData)
    kaikkiOidit.map(oid => oid -> koulusivistyskieli).toMap
  }

  def fetchKoulusivistyskieletMassa(oppijaOids: Set[String]): Future[Map[String, Seq[String]]] = {
    val query = KoskiMassaluovutusQueryParams(
      "sure-oppijat",
      "application/json",
      Some(oppijaOids),
      muuttuneetJälkeen = None
    )
    callKoskiToCreateMassaluovutusQuery(query).flatMap(queryResult =>
      resolveKoulusivistyskieletFromMassaluovutus(queryResult.resultsUrl.get)
    )
  }

  def resolveKoulusivistyskieletFromMassaluovutus(
    resultsUrl: String
  ): Future[Map[String, Seq[String]]] = {
    pollMassaluovutus(resultsUrl).flatMap((result: KoskiMassaluovutusQueryResponse) => {
      if (result.isFailed()) {
        logger.error(s"Virhe Kosken päässä massaluovutuskyselyssä $resultsUrl: ${result}")
        throw new RuntimeException(s"Virhe Kosken päässä massaluovutuskyselyssä $resultsUrl")
      } else if (result.isFinished()) {
        logger.info(
          s"Valmista Kosken päässä, voidaan hakea koulusivistyskielet. Tiedostoja yhteensä ${result.files.size}. $result"
        )
        result.files.foldLeft(
          Future.successful(Map[String, Seq[String]]())
        ) { case (accFuture, fileUrl) =>
          accFuture.flatMap(accResult => {
            logger.info(s"Haetaan koulusivistyskielet tiedostosta $fileUrl")
            fetchKoskiResultFile(fileUrl)
              .map(koskiContainers => koskiContainers.map(koulusivistyskieletForAliases))
              .map(_.flatten.toMap)
              .map(batchResult => {
                logger.info(
                  s"Saatiin yhteensä ${batchResult.size} uutta entryä (alias -> kielet), yhdistellään aiempiin ${accResult.size} entryyn. Eka ${batchResult.headOption}"
                )
                accResult ++ batchResult
              })
          })
        }
      } else {
        logger.info(s"Koskessa ei vielä valmista, pollataan kohta uudestaan.")
        Thread.sleep(5000)
        resolveKoulusivistyskieletFromMassaluovutus(resultsUrl)
      }
    })
  }

  def saveKoskiDataWithRetries(
    data: Seq[KoskiHenkiloContainer],
    params: KoskiSuoritusTallennusParams,
    description: String = "",
    retries: Int
  ) = {
    def saveSuoritusBatchWithRetries(retriesLeft: Int): Future[KoskiProcessingResults] = {
      try {
        logger.info(s"$description, käsitellään koski-data ${data.size} henkilölle")
        fetchPersonAliases(data)
          .flatMap(res => {
            val (henkilot, personOidsWithAliases) = res
            logger.info(
              s"$description Saatiin Koskesta ${henkilot.size} henkilöä, aliakset haettu!"
            )
            saveKoskiHenkilotAsSuorituksetAndArvosanat(henkilot, personOidsWithAliases, params)
              .map(failedAndSucceeded =>
                KoskiProcessingResults(
                  succeededHenkiloOids = failedAndSucceeded._2.toSet,
                  failedHenkiloOids = failedAndSucceeded._1.toSet
                )
              )
              .recoverWith({ case e: Exception =>
                if (retriesLeft > 0) {
                  logger.warn(
                    s"$description Virhe käsiteltäessä henkilöiden koski-tietoja, yritetään uudelleen. Uudelleenyrityksiä jäljellä: $retriesLeft",
                    e
                  )
                  Future { Thread.sleep(params.retryWaitMillis) }.flatMap(_ =>
                    saveSuoritusBatchWithRetries(retriesLeft - 1)
                  )
                } else {
                  logger.error(
                    s"$description Virhe käsiteltäessä henkilöiden koski-tietoja, ei enää uudelleenyrityksiä jäljellä.",
                    e
                  )
                  throw e
                }
              })
          })
      } catch {
        case e: Exception if retriesLeft > 0 =>
          logger.warn(
            s"$description Tapahtui virhe käsiteltäessä koskidataa: ${e.getMessage}. Uudelleenyrityksiä jäljellä ${retriesLeft}, yritetään uudelleen."
          )
          saveSuoritusBatchWithRetries(retriesLeft - 1)
        case e: Exception if retriesLeft == 0 =>
          logger.error(
            s"$description Tapahtui virhe käsiteltäessä koskidataa: ${e.getMessage}. Ei enää uudelleenyrityksiä jäljellä.",
            e
          )
          throw e
      }

    }
    saveSuoritusBatchWithRetries(retries)
  }

  private def callKoskiToCreateMassaluovutusQuery(
    queryParams: KoskiMassaluovutusQueryParams
  ): Future[KoskiMassaluovutusQueryResponse] = {
    virkailijaRestClient
      .postObjectWithCodes[KoskiMassaluovutusQueryParams, KoskiMassaluovutusQueryResponse](
        "koski.sure.massaluovutus.create-query",
        Seq(200),
        maxRetries = 2,
        resource = queryParams,
        basicAuth = true
      )
  }

  private def createAndHandleKoskiMassaluovutusQuery(
    koskiQuery: KoskiMassaluovutusQueryParams,
    params: KoskiSuoritusTallennusParams
  ): Future[KoskiProcessingResults] = {
    try {
      logger.info(s"Kutsutaan Kosken massaluovutusrajapintaa: $koskiQuery")
      val resultF = callKoskiToCreateMassaluovutusQuery(koskiQuery)
        .flatMap((baseQueryResponse: KoskiMassaluovutusQueryResponse) => {
          logger.info(
            s"Saatiin vastaus massaluovutusrajapinnalta: ${baseQueryResponse
              .copy(files = Seq.empty)}, pollataan tilaa ja käsitellään tiedostot"
          )
          pollMassaluovutusAndHandleResults(
            baseQueryResponse.resultsUrl.get,
            params
          )
        })
      resultF.onComplete {
        case Success(results) =>
          logger.info(
            s"Massaluovutusqueryn $koskiQuery käsittely onnistui. Onnistuneita oppijoita ${results.succeededHenkiloOids.size} ja epäonnistuneita ${results.failedHenkiloOids.size}."
          )
        case Failure(f) =>
          logger.error(s"Massaluovutusqueryn $koskiQuery käsittely epäonnistui: ${f.getMessage}", f)
      }
      resultF
    } catch {
      case e: Throwable =>
        logger.error(
          s"Jotain meni pieleen käsiteltäessä massaluovutusQuerya $koskiQuery, ${e.getStackTrace}",
          e
        )
        Future.failed(e)
    }
  }

  def pollIfNeeded(
    resultsUrl: String,
    previousPollResult: Option[KoskiMassaluovutusQueryResponse],
    handled: Set[String],
    waitMillis: Long
  ): Future[KoskiMassaluovutusQueryResponse] = {
    previousPollResult match {
      case Some(ppr) if ppr.isFinished() && ppr.files.size > handled.size =>
        logger.info(
          s"Kysely on valmistunut Kosken päässä, ei tarvitse pollata enää."
        )
        Future.successful(previousPollResult.get)
      case Some(ppr) if (ppr.files.size > handled.size) =>
        logger.info(s"Käsittelemättömiä tiedostoja on vielä, ei tarvita pollausta.")
        Future.successful(previousPollResult.get)
      case Some(ppr) if ppr.isFinished() =>
        logger.info(s"Valmista, lopetetaan.")
        Future.successful(previousPollResult.get)

      case _ =>
        logger.info(
          s"Pollataan lisää piakkoin! Koskessa valmiina ${previousPollResult.map(_.files).getOrElse(Seq.empty).size}"
        )
        Thread.sleep(waitMillis)
        pollMassaluovutus(resultsUrl).map(result =>
          if (result.isFailed()) {
            logger.error(s"Virhe Kosken päässä kyselyssä $resultsUrl: $result")
            throw new RuntimeException(s"Virhe Kosken päässä kyselyssä $resultsUrl: $result")
          } else result
        )
    }
  }

  def pollMassaluovutusAndHandleResults(
    resultsUrl: String,
    params: KoskiSuoritusTallennusParams
  ): Future[KoskiProcessingResults] = {
    var handled: Set[String] = Set[String]()

    def pollAndHandleUntilReady(
      accProcessingResults: KoskiProcessingResults,
      previousPollResult: Option[KoskiMassaluovutusQueryResponse]
    ): Future[KoskiProcessingResults] = {
      val pollResultF: Future[KoskiMassaluovutusQueryResponse] =
        pollIfNeeded(resultsUrl, previousPollResult, handled, params.massaluovutusPollWaitMillis)
      pollResultF.flatMap(massaluovutusQueryResponse => {
        logger.info(
          s"Koskessa valmiina: ${massaluovutusQueryResponse.files.size} tiedostoa (${massaluovutusQueryResponse.progress}), käsitelty aiemmin ${handled.size} tiedostoa."
        )

        val filesReadyInKoski: Set[String] = massaluovutusQueryResponse.files.toSet
        val nextUnhandledFile: Option[String] = (filesReadyInKoski -- handled).headOption

        nextUnhandledFile match {
          case Some(fileUrl) =>
            logger.info(s"Käsitellään tiedosto $fileUrl")
            handled = handled ++ nextUnhandledFile
            val description: String =
              s"KoskiMassaluovutus, käsitellään tiedosto nro ${handled.size}"
            val batchResult = fetchKoskiResultFile(fileUrl).flatMap(fileResult => {
              saveKoskiDataWithRetries(fileResult, params, description, 3)
            })
            val combinedResultsF = batchResult.map(batchResults => {
              accProcessingResults.copy(
                succeededHenkiloOids =
                  accProcessingResults.succeededHenkiloOids ++ batchResults.succeededHenkiloOids,
                failedHenkiloOids =
                  accProcessingResults.failedHenkiloOids ++ batchResults.failedHenkiloOids
              )
            })
            combinedResultsF.flatMap(combinedResults =>
              pollAndHandleUntilReady(combinedResults, Some(massaluovutusQueryResponse))
            )
          case None if !massaluovutusQueryResponse.isFinished() =>
            logger.info(s"Ei käsiteltävää, pollataan kohta uudestaan.")
            pollAndHandleUntilReady(accProcessingResults, Some(massaluovutusQueryResponse))
          case None if massaluovutusQueryResponse.isFinished() =>
            logger.info(
              s"Valmista. ${accProcessingResults.succeededHenkiloOids.size} onnistunutta ja ${accProcessingResults.failedHenkiloOids.size} epäonnistunutta henkilöä. Lopetetaan! $massaluovutusQueryResponse"
            )
            Future.successful(accProcessingResults)
        }
      })
    }

    pollAndHandleUntilReady(KoskiProcessingResults(Set[String](), Set[String]()), None)
  }

  def pollMassaluovutus(url: String) = {
    logger.info(s"Pollataan massaluovutuskyselyä, url $url")
    virkailijaRestClient
      .readObjectFromUrl[KoskiMassaluovutusQueryResponse](
        url,
        Seq(200),
        2,
        basicAuth = true
      )
  }

  def fetchKoskiResultFile(fileUrl: String): Future[Seq[KoskiHenkiloContainer]] = {
    try {
      logger.info(s"Haetaan massaluovutus-tulostiedosto $fileUrl")
      val koskiTiedotF = virkailijaRestClient
        .readKoskiMassaluovutusResultFromUrl[Seq[KoskiMassaluovutusFileResult]](
          fileUrl,
          Seq(200),
          maxRetries = 2
        )
        .map((koskiTiedotResult: Seq[KoskiMassaluovutusFileResult]) => {
          val henkiloContainers: Seq[KoskiHenkiloContainer] = koskiTiedotResult
            .map(kv =>
              KoskiHenkiloContainer(
                KoskiHenkilo(
                  Some(kv.oppijaOid),
                  Some(kv.kaikkiOidit),
                  None,
                  None,
                  None,
                  None,
                  None
                ),
                kv.opiskeluoikeudet
              )
            )
            .toList
          if (henkiloContainers.isEmpty)
            logger.warn(s"Jostain syystä tiedostosta $fileUrl ei löytynyt oppijoita...")
          henkiloContainers
        })
      koskiTiedotF
    } catch {
      case e: Exception =>
        logger.error(s"Virhe käsiteltäessä tuloksia osoitteesta $fileUrl", e)
        Future.failed(e)
    }
  }

  val massaluovutusMaxOppijaOids: Int = 5000
  def handleKoskiRefreshForOppijaOids(
    oppijaOids: Set[String],
    params: KoskiSuoritusTallennusParams
  ) = {
    if (oppijaOids.isEmpty) {
      Future.successful(KoskiProcessingResults(Set[String](), Set[String]()))
    } else {
      val groupedOids: Seq[(Set[String], Int)] =
        oppijaOids.grouped(massaluovutusMaxOppijaOids).zipWithIndex.toSeq
      logger.info(s"Haetaan Koski-tiedot ${oppijaOids.size} oppijalle ${groupedOids.size} erässä")
      val result = groupedOids.foldLeft(
        Future.successful(KoskiProcessingResults(Set[String](), Set[String]()))
      ) { case (accFuture, (oidBatch, batchNr)) =>
        accFuture.flatMap(accResult => {
          logger.info(s"Käsitellään erä ${batchNr + 1}/${groupedOids.size}")
          createAndHandleKoskiMassaluovutusQuery(
            KoskiMassaluovutusQueryParams(
              "sure-oppijat",
              "application/json",
              Some(oidBatch),
              muuttuneetJälkeen = None
            ),
            params
          ).map(batchResult =>
            KoskiProcessingResults(
              succeededHenkiloOids =
                accResult.succeededHenkiloOids ++ batchResult.succeededHenkiloOids,
              failedHenkiloOids = accResult.failedHenkiloOids ++ batchResult.failedHenkiloOids
            )
          )
        })
      }
      result.onComplete {
        case Success(processingResults) =>
          logger.info(
            s"handleKoskiRefreshForOppijaOids : saatiin käsiteltyä Koski-tiedot ${oppijaOids.size} oppijalle. Onnistuneita ${processingResults.succeededHenkiloOids.size}, epäonnistuneita ${processingResults.failedHenkiloOids.size}. ${processingResults
              .getFailedStr()}"
          )
        case Failure(f) =>
          logger.error(
            s"handleKoskiRefreshForOppijaOids : jotain meni vikaan muutosten käsittelyssä ${oppijaOids.size} oppijalle.  ${f.getMessage}",
            f
          )
      }
      result
    }
  }

  //Todo, joku validointi aikaleiman muodolle? vvvv-kk-ppThh:mm:ss
  def handleKoskiRefreshMuuttunutJalkeen(
    muuttunutJalkeen: String,
    params: KoskiSuoritusTallennusParams
  ): Future[KoskiProcessingResults] = {
    createAndHandleKoskiMassaluovutusQuery(
      KoskiMassaluovutusQueryParams(
        "sure-muuttuneet",
        "application/json",
        None,
        muuttuneetJälkeen = Some(muuttunutJalkeen)
      ),
      params
    )
  }

  def resolveKoulusivistyskieli(henkilo: KoskiHenkiloContainer): Seq[String] = {
    val validitSuoritukset = henkilo.opiskeluoikeudet
      .filter(o => o.tila.determineSuoritusTila == "VALMIS")
      .flatMap(o => o.suoritukset.filter(s => s.isLukionOrPerusopetuksenoppimaara()))

    validitSuoritukset
      .flatMap(s => s.koulusivistyskieli.map(k => k.map(_.koodiarvo)))
      .flatten
      .distinct
  }

  private def fetchKoulusivistyskieletInBatches(
    oidBatches: Seq[Set[String]],
    acc: Map[String, Seq[String]]
  ): Future[Map[String, Seq[String]]] = {
    if (oidBatches.isEmpty) {
      Future(acc)
    } else {
      logger.info(s"Haetaan koulusivistyskieliä ${oidBatches.head.size} kokoiselle batchille.")
      fetchKoulusivistyskieletMassa(oidBatches.head).flatMap((result: Map[String, Seq[String]]) => {
        fetchKoulusivistyskieletInBatches(oidBatches.tail, acc ++ result)
      })
    }
  }

  private def fetchKoulusivistyskieletForReal(
    oppijaOids: Seq[String]
  ): Future[Map[String, Seq[String]]] = {
    val grouped = oppijaOids.toSet.grouped(50).toSeq
    logger.info(
      s"Haetaan oikeasti kolusivistyskieli ${oppijaOids.size} oppijalle ${grouped.size} erässä Koskesta"
    )
    fetchKoulusivistyskieletInBatches(grouped, Map[String, Seq[String]]())

  }

  override def fetchKoulusivistyskielet(
    oppijaOids: Seq[String]
  ): Future[Map[String, Seq[String]]] = {
    logger.info(s"Pyydetty Koskesta koulusivistyskieli ${oppijaOids.size} henkilölle.")

    val cached = koskiKoulusivistyskieliCache.getAllPresent(oppijaOids)
    val missing = oppijaOids.filterNot(cached.keys.toSet.contains(_))
    logger.info(
      s"Välimuistista saatu ${cached.keys.size} henkilön koulusivistyskieli, haetaan suoraan Koskesta tiedot ${missing.size} henkilölle."
    )

    val fetched = fetchKoulusivistyskieletForReal(missing)
    fetched.foreach { f =>
      logger.info(
        s"Koski palautti ${f.keys.size} henkilön koulusivistyskielen, tallennetaan välimuistiin."
      )
      koskiKoulusivistyskieliCache.putAll(f)
    }

    fetched.zipWith(Future.successful(cached))(_ ++ _)
  }

  override def updateHenkilot(
    oppijaOids: Set[String],
    params: KoskiSuoritusTallennusParams
  ): Future[(Seq[String], Seq[String])] = {
    val oppijat = fetchHenkilot(oppijaOids)
    oppijat
      .flatMap(fetchPersonAliases)
      .flatMap(res => {
        val (henkilot, personOidsWithAliases) = res
        logger.info(s"Saatiin Koskesta ${henkilot.size} henkilöä, aliakset haettu!")
        saveKoskiHenkilotAsSuorituksetAndArvosanat(henkilot, personOidsWithAliases, params)
      })
  }

  override def updateHenkilotNewMuuttunutJalkeen(
    muuttunutJalkeen: String,
    params: KoskiSuoritusTallennusParams
  ) = {
    logger.info(s"Update henkilot new muuttunut jalkeen!")
    handleKoskiRefreshMuuttunutJalkeen(muuttunutJalkeen, params)
  }

  override def updateHenkilotNew(
    oppijaOids: Set[String],
    params: KoskiSuoritusTallennusParams
  ) = {
    handleKoskiRefreshForOppijaOids(oppijaOids, params)
  }

  //Poistaa KoskiHenkiloContainerin sisältä sellaiset opiskeluoikeudet, joilla ei ole oppilaitosta jolla on määritelty oid.
  //Vaaditaan lisäksi, että käsiteltävillä opiskeluoikeuksilla on ainakin yksi tilatieto.
  private def removeOpiskeluoikeudesWithoutDefinedOppilaitosAndOppilaitosOids(
    data: Seq[KoskiHenkiloContainer]
  ): Seq[KoskiHenkiloContainer] = {
    data.flatMap(container => {
      val oikeudet = container.opiskeluoikeudet.filter(_.isStateContainingOpiskeluoikeus)
      if (oikeudet.nonEmpty) {
        if (container.opiskeluoikeudet.size > oikeudet.size) {
          logger.info(
            s"Filtteröitiin henkilöltä ${container.henkilö.oid} ${(container.opiskeluoikeudet.size - oikeudet.size)} opiskeluoikeutta, joista puuttui oppilaitos tai opiskeluoikeuden tilatieto."
          )
        }
        Seq(container.copy(opiskeluoikeudet = oikeudet))
      } else {
        if (container.opiskeluoikeudet.nonEmpty) {
          logger.info(
            s"Filtteröitiin henkilöltä ${container.henkilö.oid} ${container.opiskeluoikeudet.size} opiskeluoikeutta, joista puuttui oppilaitos tai opiskeluoikeuden tilatieto."
          )
        }
        Seq()
      }
    })
  }

  private def saveKoskiHenkilotAsSuorituksetAndArvosanat(
    henkilot: Seq[KoskiHenkiloContainer],
    personOidsWithAliases: PersonOidsWithAliases,
    params: KoskiSuoritusTallennusParams
  ): Future[(Seq[String], Seq[String])] = {
    val filteredHenkilot: Seq[KoskiHenkiloContainer] =
      removeOpiskeluoikeudesWithoutDefinedOppilaitosAndOppilaitosOids(henkilot)
    if (filteredHenkilot.size < henkilot.size) {
      logger.info(
        s"saveKoskiHenkilotAsSuorituksetAndArvosanat: Filteröitiin ${henkilot.size - filteredHenkilot.size} henkilöä."
      )
    }
    val loytyyHenkiloOidi = filteredHenkilot.filter(_.henkilö.oid.isDefined)
    if (loytyyHenkiloOidi.size < filteredHenkilot.size) {
      logger.info(
        s"saveKoskiHenkilotAsSuorituksetAndArvosanat: Filteröitiin ${filteredHenkilot.size - loytyyHenkiloOidi.size} henkilöä joilla ei oidia."
      )
    }
    val henkiloOidToHenkilo: Future[Map[String, Henkilo]] =
      if (params.saveSeiskaKasiJaValmistava) {
        logger.info(s"hep! Haetaan lisää onr-dataa...")
        oppijaNumeroRekisteri.getByOids(loytyyHenkiloOidi.flatMap(_.henkilö.oid).toSet)
      } else
        Future.successful(Map.empty)

    henkiloOidToHenkilo.flatMap(henkilot =>
      Future
        .sequence(
          loytyyHenkiloOidi.map(henkilo =>
            (try {
              koskiDataHandler.processHenkilonTiedotKoskesta(
                henkilo,
                personOidsWithAliases.intersect(Set(henkilo.henkilö.oid.get)),
                params,
                henkilot.get(henkilo.henkilö.oid.get)
              )
            } catch {
              case e: Exception =>
                logger.error(
                  s"Koskitietojen päivitys henkilölle ${henkilo.henkilö.oid} epäonnistui: $e"
                )
                Future.successful(Seq(Left(e)))
            }).map(results => {
              val es = results.collect { case Left(e) => e }
              es.foreach(e =>
                logger.error(
                  s"Koskitietojen tallennus henkilölle ${henkilo.henkilö.oid.get} epäonnistui",
                  e
                )
              )
              if (es.isEmpty) {
                logger.info(
                  s"Koskitietojen tallennus henkilölle ${henkilo.henkilö.oid.get} onnistui"
                )
                Right(henkilo.henkilö.oid.get)
              } else {
                Left(henkilo.henkilö.oid.get)
              }
            })
          )
        )
        .map(results =>
          (results.collect { case Left(oid) => oid }, results.collect { case Right(oid) => oid })
        )
    )
  }
}
