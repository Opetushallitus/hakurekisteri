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
  AtaruHenkiloSearchParams,
  HakemuksenHarkinnanvaraisuus,
  IHakemusService
}
import fi.vm.sade.hakurekisteri.integration.haku.{AllHaut, Haku, HakuRequest}
import fi.vm.sade.hakurekisteri.integration.kooste.IKoosteService
import fi.vm.sade.utils.slf4j.Logging

import java.util.UUID
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.collection.parallel.ForkJoinTaskSupport
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
    10,
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
  private def processHakemusChunksForType[T](
    executionId: String,
    tyyppi: String,
    hakuOid: String,
    hakemusChunks: Seq[List[AtaruHakemuksenHenkilotiedot]],
    chunkResultFn: (String, Seq[AtaruHakemuksenHenkilotiedot]) => Future[Seq[T]],
    totalCount: Int,
    fileCounter: Int,
    retriesLeft: Int
  ): Either[Throwable, Int] = {
    if (hakemusChunks.isEmpty) {
      logger.info(s"Ei enää hakemuksia haulle $hakuOid!")
      Right(totalCount)
    } else {
      logger.info(
        s"Muodostetaan tyypin $tyyppi koostepalvelusiirtotiedosto haulle ${hakuOid}, erä ${fileCounter}"
      )

      val batchResultF = chunkResultFn(hakuOid, hakemusChunks.head).map(batchRes => {
        logger.info(
          s"${Thread.currentThread().getName} Tallennetaan tiedosto haulle $hakuOid, erä $fileCounter"
        )
        s3Client
          .saveSiirtotiedosto[T](
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
          logger.info(
            s"${Thread.currentThread().getName} Odotellaan tuloksia haulle $hakuOid, erä $fileCounter"
          )
          Right(Await.result(batchResultF, 4.minutes))
        } catch {
          case t: Throwable =>
            logger.error(
              s"$tyyppi Haun $hakuOid erän ${fileCounter} prosessointi epäonnistui: ${t.getMessage}. Uudelleenyrityksiä jäljellä $retriesLeft."
            )
            Left(t)
        }
      batchResult match {
        case Left(t) if retriesLeft > 0 =>
          logger.warn(
            s"$tyyppi Haun $hakuOid erän ${fileCounter} prosessointi epäonnistui: ${t.getMessage}. Uudelleenyrityksiä jäljellä $retriesLeft, yritetään uudelleen."
          )
          processHakemusChunksForType[T](
            executionId,
            tyyppi,
            hakuOid,
            hakemusChunks,
            chunkResultFn,
            totalCount,
            fileCounter,
            retriesLeft - 1
          )
        case Left(t) =>
          logger.error(
            s"$tyyppi Haun $hakuOid erän ${fileCounter} prosessointi epäonnistui: ${t.getMessage}. Ei enää uudelleenyrityksiä jäljellä.",
            t
          )
          Left(t)

        case Right(r) =>
          processHakemusChunksForType(
            executionId,
            tyyppi,
            hakuOid,
            hakemusChunks.tail,
            chunkResultFn,
            totalCount + r,
            fileCounter + 1,
            retriesLeft
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
      20,
      "koostepalvelu-siirtotiedosto-executor"
    )

    val allHakuOids = harkinnanvaraisuusHakuOids.union(proxySuorituksetHakuOids)
    val processedHakus = new AtomicReference[Int](0)
    val hakuOidGroups = allHakuOids.grouped(allHakuOids.size / 8 + 1).toList.par

    val pool = new ForkJoinTaskSupport(new ForkJoinPool(10))

    hakuOidGroups.tasksupport = pool
    val allResults: List[SiirtotiedostoResultForHaku] = hakuOidGroups
      .flatMap(hakuOids => {
        logger.info(s"Käsitellään ${hakuOids.size} hakua.......")
        hakuOids.flatMap(hakuOid => {
          val started = System.currentTimeMillis()
          logger.info(s"${Thread.currentThread().getName} Käsitellään haku $hakuOid")
          val hakemusOids: Future[List[AtaruHakemuksenHenkilotiedot]] = hakemusService
            .ataruhakemustenHenkilot(
              AtaruHenkiloSearchParams(hakuOid = Some(hakuOid), hakukohdeOids = None)
            )

          val hakemusOidsRes = Await.result(hakemusOids, 1.minutes).grouped(1000).toSeq
          logger.info(
            s"${Thread.currentThread().getName} Saatiin ${hakemusOidsRes.size} hakemuserää haulle ${hakuOid}"
          )

          val harkinannvaraisuudetF = if (harkinnanvaraisuusHakuOids.contains(hakuOid)) {
            val resultFunction = (hakuOid: String, chunk: Seq[AtaruHakemuksenHenkilotiedot]) =>
              koosteService.getHarkinnanvaraisuudetForHakemusOids(chunk.map(_.oid))

            Some(
              Future(
                processHakemusChunksForType[HakemuksenHarkinnanvaraisuus](
                  executionId,
                  "harkinnanvaraisuudet",
                  hakuOid,
                  hakemusChunks = hakemusOidsRes,
                  resultFunction,
                  totalCount = 0,
                  fileCounter = 1,
                  3 //Todo, what is a sensible amount of retries for the whole process?
                )
              )
            )
          } else {
            None
          }

          val proxySuorituksetF = if (proxySuorituksetHakuOids.contains(hakuOid)) {
            val resultFunction = (hakuOid: String, chunk: Seq[AtaruHakemuksenHenkilotiedot]) =>
              koosteService
                .getProxysuorituksetForHakemusOids(hakuOid, chunk.map(_.oid))
                .map(rawChunkResult => {
                  val personOidToHakemusOidMap = chunk.map(ht => ht.personOid.get -> ht.oid).toMap
                  rawChunkResult
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
                })
            Some(
              Future(
                processHakemusChunksForType[SiirtotiedostoProxySuoritukset](
                  executionId,
                  "proxysuoritukset",
                  hakuOid,
                  hakemusOidsRes,
                  resultFunction,
                  0,
                  1,
                  2
                )
              )
            )
          } else {
            None
          }

          val harkinnanvaraisuudetResult: Option[SiirtotiedostoResultForHaku] =
            harkinannvaraisuudetF.map((future: Future[Either[Throwable, Int]]) => {
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
              "ensikertalaiset",
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
        entityTotals = mainResults.info.entityTotals,
        dailyResults = dailyResults
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
    process.copy(
      info = SiirtotiedostoProcessInfo(resultCounts, Seq.empty),
      finishedSuccessfully = true
    )
  }

}

class OvaraServiceMock extends IOvaraService {
  override def formSiirtotiedostotPaged(process: SiirtotiedostoProcess) = ???

  override def formEnsikertalainenSiirtotiedostoForHakus(
    hakuOids: Set[String]
  ): Seq[SiirtotiedostoResultForHaku] = ???

  override def muodostaSeuraavaSiirtotiedosto(): SiirtotiedostoProcess = ???
}
