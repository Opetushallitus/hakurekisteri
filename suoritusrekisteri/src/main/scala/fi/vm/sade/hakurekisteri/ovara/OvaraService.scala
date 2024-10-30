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
    hakuOids: Seq[String]
  ): Seq[SiirtotiedostoResultForHaku]
}

case class SiirtotiedostoResultForHaku(hakuOid: String, total: Int, error: Option[Throwable])

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
    vainAktiiviset: Boolean,
    executionId: String
  ): Seq[SiirtotiedostoResultForHaku] = {
    implicit val to: Timeout = Timeout(5.minutes)

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
          logger.info(s"$executionId HakuCache ei vielä valmis, odotetaan $MILLIS_TO_WAIT ms")
          Thread.sleep(MILLIS_TO_WAIT)
          waitForHautCache(millisToWaitLeft - MILLIS_TO_WAIT)
        }
      } else {
        logger.error(s"$executionId Hakuja ei saatu ladattua")
        throw new RuntimeException(s"Hakuja ei saatu ladattua")
      }
    }

    logger.info(s"$executionId Muodostetaan päivittäiset, vain aktiiviset: $vainAktiiviset")
    try {
      val haut: Seq[Haku] = waitForHautCache(600 * 1000)

      //(Aktiiviset) toisen asteen yhteishaut
      val hautForHarkinnanvaraisuus = haut
        .filter(haku =>
          (!vainAktiiviset || haku.isActive) && haku.toisenAsteenHaku && haku.hakutapaUri
            .startsWith("hakutapa_01")
        )
        .map(_.oid)
        .toSet
      //(Aktiiviset) toisen asteen haut
      val hautForPohjakoulutus =
        haut
          .filter(haku => (!vainAktiiviset || haku.isActive) && haku.toisenAsteenHaku)
          .map(_.oid)
          .toSet
      //(Aktiiviset) kk-haut
      processHakusForHarkinnanvaraisuusAndPohjakoulutus(
        hautForHarkinnanvaraisuus.take(5),
        hautForPohjakoulutus.take(5)
      )

      val hautForEnsikertalaisuus =
        haut.filter(haku => (!vainAktiiviset || haku.isActive) && haku.kkHaku).map(_.oid)
      logger.info(
        s"$executionId Löydettiin ${hautForEnsikertalaisuus.size} kiinnostavaa hakua yhteensä ${haut.size} hausta. Vain aktiiviset: $vainAktiiviset"
      )
      formEnsikertalainenSiirtotiedostoForHakus(hautForEnsikertalaisuus)
    } catch {
      case t: Throwable =>
        logger.error(
          s"$executionId Ensikertalaisten siirtotiedostojen muodostaminen epäonnistui: ",
          t
        )
        throw t
    }
  }

  @tailrec
  private def formHarkinnanvaraisuudetSiirtotiedostoForHaku(
    executionId: String,
    tyyppi: String,
    hakuOid: String,
    hakemusChunks: Seq[Seq[String]],
    totalCount: Int,
    fileCounter: Int
  ): Either[Throwable, Int] = {
    if (hakemusChunks.isEmpty) {
      logger.info("Ei enää hakemuksia!")
      Right(totalCount)
    } else {
      logger.info(
        s"Muodostetaan HARKINNANVARAISUUS koostepalvelusiirtotiedosto haulle ${hakuOid}, erä ${fileCounter + 1}"
      )

      val safeResult: Future[Int] = koosteService
        .getHarkinnanvaraisuudetForHakemusOids(hakemusChunks.head)
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
          Right(Await.result(safeResult, 5.minutes))
        } catch {
          case t: Throwable =>
            logger.info(s"Erän prosessointi epäonnistui: ${t.getMessage}", t)
            Left(t)
        }
      batchResult match {
        case Left(t) => Left(t)
        case Right(r) =>
          formHarkinnanvaraisuudetSiirtotiedostoForHaku(
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
  private def formProxySuorituksetSiirtotiedostoForHaku(
    executionId: String,
    tyyppi: String,
    hakuOid: String,
    hakemusChunks: Seq[Seq[String]],
    totalCount: Int,
    fileCounter: Int
  ): Either[Throwable, Int] = {
    if (hakemusChunks.isEmpty) {
      logger.info("Ei enää hakemuksia!")
      Right(totalCount)
    } else {
      logger.info(
        s"$tyyppi Muodostetaan PROXYSUORITUKSET koostepalvelusiirtotiedosto haulle ${hakuOid}, erä ${fileCounter + 1}"
      )

      val safeResult: Future[Int] = koosteService
        .getProxysuorituksetForHakemusOids(hakuOid, hakemusChunks.head)
        .map(rawChunkResult => {
          logger.info(s"$tyyppi Tallennetaan tiedosto...")
          val parsedResult = rawChunkResult
            .map(singleResult => {
              SiirtotiedostoProxySuoritukset(
                hakemusOid = "hakemusOid",
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
      val batchResult: Either[Throwable, Int] =
        try {
          Right(Await.result(safeResult, 5.minutes))
        } catch {
          case t: Throwable =>
            logger.info(s"$tyyppi Erän prosessointi epäonnistui: ${t.getMessage}", t)
            Left(t)
        }
      batchResult match {
        case Left(t) => Left(t)
        case Right(r) =>
          formProxySuorituksetSiirtotiedostoForHaku(
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
    harkinnanvaraisuusHakuOids: Set[String],
    pohjakoulutusHakuOids: Set[String]
  ) = {
    val allHakuOids = harkinnanvaraisuusHakuOids.union(pohjakoulutusHakuOids)
    val allResults: Set[(String, Int, Int)] = allHakuOids.map(hakuOid => {
      logger.info(s"Käsitellään haku $hakuOid")
      val hakemusOids: Future[Seq[String]] = hakemusService
        .ataruhakemustenHenkilot(
          AtaruHenkiloSearchParams(hakuOid = Some(hakuOid), hakukohdeOids = None)
        )
        .map(_.map(_.oid))

      val hakemusOidsRes = Await.result(hakemusOids, 5.minutes).grouped(50).toSeq
      logger.info(s"Saatiin jokunen hakemusOid (${hakemusOidsRes.size})")
      val harkinannvaraisuudetF = Future(
        formHarkinnanvaraisuudetSiirtotiedostoForHaku(
          "exec-id",
          "harkinnanvaraisuudet",
          hakuOid,
          hakemusChunks = hakemusOidsRes,
          totalCount = 0,
          fileCounter = 0
        )
      )
      val proxySuorituksetF = Future(
        formProxySuorituksetSiirtotiedostoForHaku(
          "exec-id",
          "proxysuoritukset",
          hakuOid,
          hakemusChunks = hakemusOidsRes,
          totalCount = 0,
          fileCounter = 0
        )
      )
      val harkinnanvaraisuudetResult = Await.result(harkinannvaraisuudetF, 20.minutes)
      val proxySuorituksetResult = Await.result(proxySuorituksetF, 20.minutes)
      logger.info(s"Kaikki valmista  (${hakemusOidsRes.size})")
      (hakuOid, 1, 1)
    })
    logger.info("Yhdistetään")
    //val allResultsCombined = Future.sequence(allResults.flatMap(r => Seq(r._2) :+ r._3))
    //val awaited = Await.result(allResultsCombined, 15.minutes)
    logger.info(s"pling! ${allResults}")
  }

  //Ensivaiheessa ajetaan tämä kaikille kk-hauille kerran, myöhemmin riittää synkata kerran päivässä aktiivisten kk-hakujen tiedot
  def formEnsikertalainenSiirtotiedostoForHakus(
    hakuOids: Seq[String]
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
            results.updateAndGet(r => SiirtotiedostoResultForHaku(result._1, result._2, None) :: r)
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
              results.updateAndGet(r => SiirtotiedostoResultForHaku(hakuOid, 0, Some(t)) :: r)
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

      val ensikertalaisetResults =
        if (formEnsikertalaisuudet) {
          logger.info(s"$executionId Muo")
          triggerDailyProcessing(true, executionId)
        } else Seq.empty

      val combinedInfo = SiirtotiedostoProcessInfo(
        mainResults.info.entityTotals ++ ensikertalaisetResults
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
    hakuOids: Seq[String]
  ): Seq[SiirtotiedostoResultForHaku] = ???

  override def muodostaSeuraavaSiirtotiedosto(): SiirtotiedostoProcess = ???
}
