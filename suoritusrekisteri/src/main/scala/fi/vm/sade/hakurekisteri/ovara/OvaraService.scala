package fi.vm.sade.hakurekisteri.ovara

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.ensikertalainen.{
  Ensikertalainen,
  HaunEnsikertalaisetQuery,
  MenettamisenPeruste
}
import fi.vm.sade.hakurekisteri.integration.ExecutorUtil
import fi.vm.sade.hakurekisteri.integration.hakemus.{
  AtaruHakemuksenHenkilotiedot,
  AtaruHakemusToinenAste,
  AtaruHenkiloSearchParams,
  AtaruSearchParams,
  HakemuksenHarkinnanvaraisuus,
  IHakemusService
}
import fi.vm.sade.hakurekisteri.integration.haku.{AllHaut, Haku, HakuRequest}
import fi.vm.sade.hakurekisteri.integration.kooste.{IKoosteService, KoosteService}
import fi.vm.sade.utils.slf4j.Logging

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.DurationInt

/**
  * Muodostetaan siirtotiedostot kaikille neljälle tyypille. Jos dataa on aikavälillä paljon, muodostuu useita tiedostoja per tyyppi.
  * Tiedostot tallennetaan s3:seen.
  */
trait IOvaraService {
  def muodostaSeuraavaSiirtotiedosto(): SiirtotiedostoProcess
  def formSiirtotiedostotPaged(process: SiirtotiedostoProcess): SiirtotiedostoProcess
  def formEnsikertalainenSiirtotiedostoForHakus(
    hakuOids: Set[String]
  ): Seq[SiirtotiedostoResultForHaku]
}

case class SiirtotiedostoResultForHaku(
  hakuOid: String,
  tyyppi: String,
  total: Int,
  error: Option[Throwable]
)

case class DailyProcessingParams(
  executionId: String,
  vainAktiiviset: Boolean,
  ensikertalaisuudet: Boolean,
  harkinnanvaraisuudet: Boolean,
  proxySuoritukset: Boolean
)

class OvaraService(
  db: OvaraDbRepository,
  s3Client: SiirtotiedostoClient,
  ensikertalainenActor: ActorRef,
  hakuActor: ActorRef,
  pageSize: Int,
  hakemusService: IHakemusService,
  koosteService: IKoosteService
) extends IOvaraService
    with Logging {

  implicit val ec: ExecutionContext = ExecutorUtil.createExecutor(
    6,
    getClass.getSimpleName
  )

  @tailrec
  private def saveInSiirtotiedostoPaged[T](
    params: SiirtotiedostoPagingParams,
    pageFunction: SiirtotiedostoPagingParams => Seq[T]
  ): Long = {
    val pageResults = pageFunction(params)
    if (pageResults.isEmpty) {
      logger.info(
        s"(${params.executionId}) Saatiin tyhjä sivu, lopetetaan. Haettiin yhteensä ${params.offset} kpl tyyppiä ${params.tyyppi}"
      )
      params.offset
    } else {
      logger.info(
        s"(${params.executionId}) Saatiin sivu (${pageResults.size} kpl), haettu yhteensä ${params.offset + pageResults.size} kpl. Tallennetaan siirtotiedosto ennen seuraavan sivun hakemista. $params"
      )
      s3Client
        .saveSiirtotiedosto[T](
          params.tyyppi,
          pageResults,
          params.executionId,
          params.fileCounter,
          None
        )
      saveInSiirtotiedostoPaged(
        params
          .copy(offset = params.offset + pageResults.size, fileCounter = params.fileCounter + 1),
        pageFunction
      )
    }
  }

  private def formSiirtotiedosto[T](
    params: SiirtotiedostoPagingParams,
    pageFunction: SiirtotiedostoPagingParams => Seq[T]
  ): Either[Throwable, Long] = {
    logger.info(s"(${params.executionId}) Muodostetaan siirtotiedosto: $params")
    try {
      val count = saveInSiirtotiedostoPaged(params, pageFunction)
      Right(count)
    } catch {
      case t: Throwable =>
        logger.error(
          s"(${params.executionId}) Virhe muodostettaessa siirtotiedostoa parametreilla $params:",
          t
        )
        Left(t)
    }
  }

  case class SiirtotiedostoEnsikertalainen(
    hakuOid: String,
    henkiloOid: String,
    isEnsikertalainen: Boolean,
    menettamisenPeruste: Option[MenettamisenPeruste]
  )

  case class SiirtotiedostoProxySuoritukset(
    hakemusOid: String,
    hakuOid: String,
    henkiloOid: String,
    values: Map[String, String]
  )

  //Kerran päivässä muodostetaan uudelleen päätellyt tiedot, joista on vaikea sanoa millä hetkellä ne tarkalleen muuttuvat koska ne yhdistelevät useiden palveluiden dataa.
  //Näitä ovat ensikertalaisuudet (kk-haut), pohjakoulutus (toisen asteen haut) sekä harkinnanvaraisuus (toisen asteen yhteishaku)
  def triggerDailyProcessing(
    params: DailyProcessingParams
  ): Seq[SiirtotiedostoResultForHaku] = {
    implicit val to: Timeout = Timeout(11.minutes)

    val MILLIS_TO_WAIT = 5000
    //Odotetaan, että HakuActor saa haut ladattua cacheen.
    //Haut tarvitaan cacheen myös sitä varten, että EnsikertalaisActor kutsuu myöhemmin HakuActoria.
    @tailrec
    def waitForHautCache(millisToWaitLeft: Long = 600 * 1000): Seq[Haku] = {
      if (millisToWaitLeft > 0) {
        val allHaut: Future[AllHaut] =
          (hakuActor ? HakuRequest)
            .mapTo[AllHaut]
        val hakuResult: AllHaut = Await.result(allHaut, 10.seconds)
        if (hakuResult.haut.nonEmpty) {
          hakuResult.haut
        } else {
          logger.info(
            s"${params.executionId} HakuCache ei vielä valmis, odotetaan $MILLIS_TO_WAIT ms"
          )
          Thread.sleep(MILLIS_TO_WAIT)
          waitForHautCache(millisToWaitLeft - MILLIS_TO_WAIT)
        }
      } else {
        logger.error(s"${params.executionId} Hakuja ei saatu ladattua")
        throw new RuntimeException(s"Hakuja ei saatu ladattua")
      }
    }

    logger.info(s"${params.executionId} Muodostetaan päivittäiset, params: $params")
    try {
      val haut: Seq[Haku] = waitForHautCache(600 * 1000)

      //Harkinnanvaraisuudet (aktiivisille) toisen asteen yhteishauille
      val hautForHarkinnanvaraisuus =
        if (params.harkinnanvaraisuudet)
          haut
            .filter(haku =>
              (!params.vainAktiiviset || haku.isActive) && haku.toisenAsteenHaku && haku.hakutapaUri
                .startsWith("hakutapa_01")
            )
            .map(_.oid)
            .toSet
        else Set[String]()

      //Proxysuoritukset (aktiivisille) toisen asteen hauille
      val hautForProxysuoritukset =
        if (params.proxySuoritukset)
          haut
            .filter(haku => (!params.vainAktiiviset || haku.isActive) && haku.toisenAsteenHaku)
            .map(_.oid)
            .toSet
        else Set[String]()

      //Ensikertalaisuudet (aktiivisille) kk-hauille
      logger.info(
        s"Käsitellään ${hautForHarkinnanvaraisuus.size} haun harkinnanvaraisuudet ja ${hautForProxysuoritukset.size} haun proxySuoritukset"
      )
      val harkinnanvaraisuusAndProxysuorituksetResultsF = Future(
        processHakusForHarkinnanvaraisuusAndPohjakoulutus(
          params.executionId,
          hautForHarkinnanvaraisuus,
          hautForProxysuoritukset
        )
      )

      val hautForEnsikertalaisuus = if (params.ensikertalaisuudet) {
        haut
          .filter(haku => (!params.vainAktiiviset || haku.isActive) && haku.kkHaku)
          .map(_.oid)
          .toSet
      } else Set[String]()
      logger.info(
        s"Käsitellään ${hautForHarkinnanvaraisuus.size} haun harkinnanvaraisuudet, ${hautForProxysuoritukset.size} haun proxySuoritukset ja ${hautForEnsikertalaisuus.size} haun harkinnanvaraisuudet"
      )

      val ensikertalaisetResultsF = Future(
        formEnsikertalainenSiirtotiedostoForHakus(hautForEnsikertalaisuus)
      )

      val combinedResults = for {
        harkinnanvaraisuusPohjakoulutus <- harkinnanvaraisuusAndProxysuorituksetResultsF
        ensikertalaiset <- ensikertalaisetResultsF
      } yield {
        harkinnanvaraisuusPohjakoulutus ++ ensikertalaiset
      }
      Await.result(combinedResults, 3.hours)
    } catch {
      case t: Throwable =>
        logger.error(
          s"${params.executionId} Ensikertalaisten siirtotiedostojen muodostaminen epäonnistui: ",
          t
        )
        throw t
    }
  }

  @tailrec
  private def processHakemusChunksForHarkinnanvaraisuus(
    executionId: String,
    tyyppi: String,
    hakuOid: String,
    hakemusChunks: Seq[List[AtaruHakemuksenHenkilotiedot]],
    totalCount: Int,
    fileCounter: Int
  ): Either[Throwable, Int] = {
    if (hakemusChunks.isEmpty) {
      logger.info("Ei enää hakemuksia!")
      Right(totalCount)
    } else {
      logger.info(
        s"Muodostetaan HARKINNANVARAISUUS koostepalvelusiirtotiedosto haulle ${hakuOid}, erä ${fileCounter}"
      )

      val batchResultF: Future[Int] = koosteService
        .getHarkinnanvaraisuudetForHakemusOids(hakemusChunks.head.map(_.oid))
        .map((batchRes: Seq[HakemuksenHarkinnanvaraisuus]) => {
          logger.info("Tallennetaan tiedosto...")
          s3Client
            .saveSiirtotiedosto[HakemuksenHarkinnanvaraisuus](
              tyyppi,
              batchRes,
              executionId,
              fileCounter,
              Some(hakuOid)
            )
          batchRes.size
        })
      val batchResult: Either[Throwable, Int] =
        try {
          Right(Await.result(batchResultF, 8.minutes))
        } catch {
          case t: Throwable =>
            logger.info(s"$tyyppi Erän ${fileCounter} prosessointi epäonnistui: ${t.getMessage}", t)
            Left(t)
        }
      batchResult match {
        case Left(t) => Left(t)
        case Right(r) =>
          processHakemusChunksForHarkinnanvaraisuus(
            executionId,
            tyyppi,
            hakuOid,
            hakemusChunks.tail,
            totalCount + r,
            fileCounter + 1
          )
      }
    }
  }

  @tailrec
  private def processHakemusChunksForProxySuoritukset(
    executionId: String,
    tyyppi: String,
    hakuOid: String,
    hakemusChunks: Seq[List[AtaruHakemuksenHenkilotiedot]],
    totalCount: Int,
    fileCounter: Int
  ): Either[Throwable, Int] = {
    if (hakemusChunks.isEmpty) {
      logger.info("Ei enää hakemuksia!")
      Right(totalCount)
    } else {
      logger.info(
        s"$tyyppi Muodostetaan PROXYSUORITUKSET koostepalvelusiirtotiedosto haulle ${hakuOid}, erä ${fileCounter}"
      )
      val currentChunk = hakemusChunks.head.filter(_.personOid.isDefined)
      val personOidToHakemusOidMap = currentChunk.map(ht => ht.personOid.get -> ht.oid).toMap
      val safeResult: Future[Int] = koosteService
        .getProxysuorituksetForHakemusOids(hakuOid, currentChunk.map(_.oid))
        .map(rawChunkResult => {
          logger.info(s"$tyyppi Tallennetaan tiedosto...")
          val parsedResult = rawChunkResult
            .map(singleResult => {
              SiirtotiedostoProxySuoritukset(
                hakemusOid = personOidToHakemusOidMap.getOrElse(
                  singleResult._1,
                  throw new RuntimeException("personOid -> hakemusOid mapping not found!")
                ),
                hakuOid = hakuOid,
                henkiloOid = singleResult._1,
                values = singleResult._2
              )
            })
            .toSeq
          s3Client
            .saveSiirtotiedosto[SiirtotiedostoProxySuoritukset](
              tyyppi,
              parsedResult,
              executionId,
              fileCounter,
              Some(hakuOid)
            )
          parsedResult.size
        })
      val batchResultF: Either[Throwable, Int] =
        try {
          Right(Await.result(safeResult, 7.minutes))
        } catch {
          case t: Throwable =>
            logger.info(
              s"$tyyppi Erän prosessointi epäonnistui haulle $hakuOid: ${t.getMessage}",
              t
            )
            Left(t)
        }
      batchResultF match {
        case Left(t) => Left(t)
        case Right(r) =>
          processHakemusChunksForProxySuoritukset(
            executionId,
            tyyppi,
            hakuOid,
            hakemusChunks.tail,
            totalCount + r,
            fileCounter + 1
          )
      }
    }
  }

  def processHakusForHarkinnanvaraisuusAndPohjakoulutus(
    executionId: String,
    harkinnanvaraisuusHakuOids: Set[String],
    proxySuorituksetHakuOids: Set[String]
  ): List[SiirtotiedostoResultForHaku] = {
    implicit val ec: ExecutionContext = ExecutorUtil.createExecutor(
      8,
      "koostepalvelu-siirtotiedosto-executor"
    )
    val allHakuOids = harkinnanvaraisuusHakuOids.union(proxySuorituksetHakuOids)
    val processedHakus = new AtomicReference[Int](0)
    val hakuOidGroups = allHakuOids.grouped(allHakuOids.size / 8 + 1).toList
    val allResults: List[SiirtotiedostoResultForHaku] = hakuOidGroups.par
      .flatMap(hakuOids => {
        logger.info(s"Käsitellään ${hakuOids.size} hakua.......")
        hakuOids.flatMap(hakuOid => {
          val started = System.currentTimeMillis()
          logger.info(s"${Thread.currentThread().getName} Käsitellään haku $hakuOid")
          val hakemusOids: Future[List[AtaruHakemuksenHenkilotiedot]] = hakemusService
            .ataruhakemustenHenkilot(
              AtaruHenkiloSearchParams(hakuOid = Some(hakuOid), hakukohdeOids = None)
            )

          val hakemusOidsRes = Await.result(hakemusOids, 6.minutes).grouped(2500).toSeq
          logger.info(
            s"${Thread.currentThread().getName} Saatiin ${hakemusOidsRes.size} hakemuserää haulle ${hakuOid}"
          )

          val harkinannvaraisuudetF = if (harkinnanvaraisuusHakuOids.contains(hakuOid)) {
            Some(
              Future(
                processHakemusChunksForHarkinnanvaraisuus(
                  executionId,
                  "harkinnanvaraisuudet",
                  hakuOid,
                  hakemusChunks = hakemusOidsRes,
                  totalCount = 0,
                  fileCounter = 1
                )
              )
            )
          } else {
            None
          }

          val proxySuorituksetF = if (proxySuorituksetHakuOids.contains(hakuOid)) {
            Some(
              Future(
                processHakemusChunksForProxySuoritukset(
                  executionId,
                  "proxysuoritukset",
                  hakuOid,
                  hakemusChunks = hakemusOidsRes,
                  totalCount = 0,
                  fileCounter = 1
                )
              )
            )
          } else {
            None
          }

          val harkinnanvaraisuudetResult: Option[SiirtotiedostoResultForHaku] =
            harkinannvaraisuudetF.map(future => {
              Await.result(future, 2.hours) match {
                case Left(t) =>
                  SiirtotiedostoResultForHaku(hakuOid, "harkinnanvaraisuudet", 0, Some(t))
                case Right(resultCount) =>
                  SiirtotiedostoResultForHaku(hakuOid, "harkinnanvaraisuudet", resultCount, None)
              }
            })

          val proxySuorituksetResult: Option[SiirtotiedostoResultForHaku] =
            proxySuorituksetF.map(future => {
              Await.result(future, 2.hours) match {
                case Left(t) => SiirtotiedostoResultForHaku(hakuOid, "proxysuoritukset", 0, Some(t))
                case Right(resultCount) =>
                  SiirtotiedostoResultForHaku(hakuOid, "proxysuoritukset", resultCount, None)
              }
            })

          logger.info(
            s"${Thread.currentThread().getName} Kaikki valmista haulle ${hakuOid}: ${List(harkinnanvaraisuudetResult, proxySuorituksetResult)}! Took ${(System
              .currentTimeMillis() - started) / 1000} seconds"
          )
          logger.info(
            s"Käsitelty yhteensä ${processedHakus.updateAndGet(p => p + 1)} / ${allHakuOids.size} hakua"
          )
          List(harkinnanvaraisuudetResult, proxySuorituksetResult).filter(_.isDefined).map(_.get)
        })
      })
      .toList

    logger.info(s"pling! ${allResults}")
    allResults
      .filter(_.error.isDefined)
      .foreach(res =>
        logger.error(
          s"Haun ${res.hakuOid} ${res.tyyppi} epäonnistui: ${res.error.map(_.getMessage).getOrElse("")}"
        )
      )
    allResults
  }

  //Ensivaiheessa ajetaan tämä kaikille kk-hauille kerran, myöhemmin riittää synkata kerran päivässä aktiivisten kk-hakujen tiedot
  def formEnsikertalainenSiirtotiedostoForHakus(
    hakuOids: Set[String]
  ): Seq[SiirtotiedostoResultForHaku] = {
    val executionId = UUID.randomUUID().toString
    val fileCounter = new AtomicReference[Int](0)
    val results = new AtomicReference[List[SiirtotiedostoResultForHaku]](List.empty)
    def formEnsikertalainenSiirtotiedostoForHaku(hakuOid: String): Future[(String, Int)] = {
      implicit val to: Timeout = Timeout(30.minutes)
      logger.info(s"($executionId) Ei löytynyt lainkaan ensikertalaisuustietoja haulle $hakuOid")
      val ensikertalaiset: Future[Seq[Ensikertalainen]] =
        (ensikertalainenActor ? HaunEnsikertalaisetQuery(hakuOid)).mapTo[Seq[Ensikertalainen]]
      ensikertalaiset.map((rawEnsikertalaiset: Seq[Ensikertalainen]) => {
        logger.info(
          s"($executionId) Saatiin ${rawEnsikertalaiset.size} ensikertalaisuustietoa haulle $hakuOid. Tallennetaan siirtotiedosto."
        )
        val ensikertalaiset = rawEnsikertalaiset.map(e =>
          SiirtotiedostoEnsikertalainen(
            hakuOid,
            e.henkiloOid,
            e.menettamisenPeruste.isEmpty,
            e.menettamisenPeruste
          )
        )
        if (ensikertalaiset.nonEmpty) {
          s3Client
            .saveSiirtotiedosto[SiirtotiedostoEnsikertalainen](
              "ensikertalainen",
              ensikertalaiset,
              executionId,
              fileCounter.updateAndGet(c => c + 1)
            )
          (hakuOid, ensikertalaiset.size)
        } else {
          logger.info(
            s"($executionId) Ei löytynyt lainkaan ensikertalaisuustietoja haulle $hakuOid"
          )
          (hakuOid, 0)
        }
      })
    }

    val infoStr =
      if (hakuOids.size <= 5) s"hauille ${hakuOids.toString}" else s"${hakuOids.size} haulle."
    logger.info(s"($executionId) Muodostetaan siirtotiedostot $infoStr")
    hakuOids.par
      .foreach(hakuOid => {
        val start = System.currentTimeMillis()
        try {
          val result = Await.result(formEnsikertalainenSiirtotiedostoForHaku(hakuOid), 45.minutes)
          logger.info(
            s"($executionId) Valmista haulle $hakuOid, kesto ${System.currentTimeMillis() - start} ms"
          )
          val totalProcessed =
            results.updateAndGet(r =>
              SiirtotiedostoResultForHaku(result._1, "ensikertalaisuus", result._2, None) :: r
            )
          logger.info(
            s"Valmiina ${totalProcessed.size} / ${hakuOids.size}, onnistuneita ${totalProcessed
              .count(_.error.isEmpty)} ja epäonnistuneita ${totalProcessed.count(_.error.nonEmpty)}"
          )
        } catch {
          case t: Throwable =>
            logger
              .error(
                s"($executionId) (kesto ${System.currentTimeMillis() - start} ms) Siirtotiedoston muodostaminen haun $hakuOid ensikertalaisista epäonnistui:",
                t
              )
            val totalProcessed =
              results.updateAndGet(r =>
                SiirtotiedostoResultForHaku(hakuOid, "ensikertalaisuus", 0, Some(t)) :: r
              )
            logger.info(
              s"Valmiina ${totalProcessed.size} / ${hakuOids.size}, onnistuneita ${totalProcessed
                .count(_.error.isEmpty)} ja epäonnistuneita ${totalProcessed.count(_.error.nonEmpty)}"
            )
        }
      })
    val finalResults = results.get()
    val failed = finalResults.filter(_.error.isDefined)
    failed.foreach(result =>
      logger.error(
        s"Ei saatu muodostettua ensikertalaisten siirtotiedostoa haulle ${result.hakuOid}: ${result.error}"
      )
    )
    logger.info(s"Onnistuneita ${hakuOids.size - failed.size}, epäonnistuneita ${failed.size}")
    finalResults
  }

  def muodostaSeuraavaSiirtotiedosto = {
    val executionId = UUID.randomUUID().toString
    val latestProcessInfo: Option[SiirtotiedostoProcess] =
      db.getLatestSuccessfulProcessInfo
    logger.info(
      s"$executionId Haettiin tieto edellisestä siirtotiedostoprosessista: $latestProcessInfo"
    )

    val windowStart = latestProcessInfo match {
      case Some(processInfo) if processInfo.finishedSuccessfully => processInfo.windowEnd
      case Some(processInfo)                                     => processInfo.windowStart //retry previous
      case None                                                  => 0
    }
    val windowEnd = System.currentTimeMillis()
    val formEnsikertalaisuudet = !latestProcessInfo.exists(_.ensikertalaisuudetFormedToday)

    val newProcessInfo: SiirtotiedostoProcess =
      db.createNewProcess(executionId, windowStart, windowEnd)
        .getOrElse(throw new RuntimeException("Siirtotiedosto process does not exist!"))
    logger.info(s"$executionId Luotiin ja persistoitiin tieto luodusta: $newProcessInfo")

    try {

      val mainResults: SiirtotiedostoProcess = formSiirtotiedostotPaged(
        newProcessInfo
      )

      val dailyResults: Seq[SiirtotiedostoResultForHaku] =
        if (formEnsikertalaisuudet) {
          logger.info(s"$executionId Muodostetaan päivittäiset tiedostot")
          triggerDailyProcessing(
            DailyProcessingParams(
              executionId,
              vainAktiiviset = true,
              ensikertalaisuudet = true,
              harkinnanvaraisuudet = true,
              proxySuoritukset = true
            )
          )
        } else Seq.empty

      val combinedInfo = SiirtotiedostoProcessInfo(
        mainResults.info.entityTotals ++ dailyResults
          .map(r => "ek_" + r.hakuOid -> r.total.toLong)
          .toMap
      )

      val combinedSuccessfulResults = mainResults.copy(
        info = combinedInfo,
        ensikertalaisuudetFormedToday = formEnsikertalaisuudet
      )

      logger.info(
        s"$executionId Siirtotiedostojen muodostus valmistui, persistoidaan tulokset: $combinedSuccessfulResults"
      )
      db.persistFinishedProcess(combinedSuccessfulResults)
      combinedSuccessfulResults
    } catch {
      case t: Throwable =>
        logger.error(
          s"$executionId Virhe siirtotiedoston muodostamisessa tai persistoinnissa, merkitään virhe kantaan...",
          t
        )
        db.persistFinishedProcess(newProcessInfo.copy(errorMessage = Some(t.getMessage)))
        throw t
    }

  }

  def formSiirtotiedostotPaged(process: SiirtotiedostoProcess): SiirtotiedostoProcess = {
    logger.info(
      s"(${process.executionId}) Muodostetaan siirtotiedosto, $process"
    )
    val baseParams =
      SiirtotiedostoPagingParams(
        process.executionId,
        1,
        "",
        process.windowStart,
        process.windowEnd,
        0,
        pageSize
      )

    val suoritusResult = formSiirtotiedosto[SiirtotiedostoSuoritus](
      baseParams.copy(tyyppi = "suoritus"),
      params => db.getChangedSuoritukset(params)
    ).fold(t => throw t, c => c)
    val arvosanaResult = formSiirtotiedosto[SiirtotiedostoArvosana](
      baseParams.copy(tyyppi = "arvosana"),
      params => db.getChangedArvosanat(params)
    ).fold(t => throw t, c => c)
    val opiskelijaResult = formSiirtotiedosto[SiirtotiedostoOpiskelija](
      baseParams.copy(tyyppi = "opiskelija"),
      params => db.getChangedOpiskelijat(params)
    ).fold(t => throw t, c => c)
    val opiskeluoikeusResult = formSiirtotiedosto[SiirtotiedostoOpiskeluoikeus](
      baseParams.copy(tyyppi = "opiskeluoikeus"),
      params => db.getChangedOpiskeluoikeudet(params)
    ).fold(t => throw t, c => c)
    val resultCounts = Map(
      "suoritus" -> suoritusResult,
      "arvosana" -> arvosanaResult,
      "opiskelija" -> opiskelijaResult,
      "opiskeluoikeus" -> opiskeluoikeusResult
    )
    logger.info(s"(${process.executionId}) Siirtotiedostot muodostettu, tuloksia: $resultCounts")
    process.copy(info = SiirtotiedostoProcessInfo(resultCounts), finishedSuccessfully = true)
  }

}

class OvaraServiceMock extends IOvaraService {
  override def formSiirtotiedostotPaged(process: SiirtotiedostoProcess) = ???

  override def formEnsikertalainenSiirtotiedostoForHakus(
    hakuOids: Set[String]
  ): Seq[SiirtotiedostoResultForHaku] = ???

  override def muodostaSeuraavaSiirtotiedosto(): SiirtotiedostoProcess = ???
}
