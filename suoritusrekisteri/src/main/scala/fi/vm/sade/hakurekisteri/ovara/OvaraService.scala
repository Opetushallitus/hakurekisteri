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
import fi.vm.sade.hakurekisteri.integration.haku.{AllHaut, Haku, HakuRequest}
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
  def formEnsikertalainenSiirtotiedostoForHakus(hakuOids: Seq[String]): String
}

class OvaraService(
  db: OvaraDbRepository,
  s3Client: SiirtotiedostoClient,
  ensikertalainenActor: ActorRef,
  hakuActor: ActorRef,
  pageSize: Int
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
        .saveSiirtotiedosto[T](params.tyyppi, pageResults, params.executionId, params.fileCounter)
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

  //Haetaan hakujen oidit ja synkataan ensikertalaiset näille
  def triggerEnsikertalaiset(vainAktiiviset: Boolean) = {
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
          logger.info(s"HakuCache ei vielä valmis, odotetaan $MILLIS_TO_WAIT ms")
          Thread.sleep(MILLIS_TO_WAIT)
          waitForHautCache(millisToWaitLeft - MILLIS_TO_WAIT)
        }
      } else {
        throw new RuntimeException(s"Hakuja ei saatu ladattua")
      }
    }

    logger.info(s"Muodostetaan ensikertalaisuudet, vain aktiiviset: $vainAktiiviset")
    try {
      val haut: Seq[Haku] = waitForHautCache(600 * 1000)
      val kiinnostavat =
        haut.filter(haku => (!vainAktiiviset || haku.isActive) && haku.kkHaku).map(_.oid)
      logger.info(
        s"Löydettiin ${kiinnostavat.size} kiinnostavaa hakua yhteensä ${haut.size} hausta. Vain aktiiviset: $vainAktiiviset"
      )
      formEnsikertalainenSiirtotiedostoForHakus(kiinnostavat)
    } catch {
      case t: Throwable =>
        logger.error(s"Ensikertalaisten siirtotiedostojen muodostaminen epäonnistui: ", t)
    }
  }

  //Ensivaiheessa ajetaan tämä kaikille kk-hauille kerran, myöhemmin riittää synkata kerran päivässä aktiivisten kk-hakujen tiedot
  def formEnsikertalainenSiirtotiedostoForHakus(hakuOids: Seq[String]): String = {
    val executionId = UUID.randomUUID().toString
    val fileCounter = new AtomicReference[Int](0)
    val results = new AtomicReference[List[(String, Option[Throwable])]](List.empty)
    def formEnsikertalainenSiirtotiedostoForHaku(hakuOid: String) = {
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
        } else {
          logger.info(
            s"($executionId) Ei löytynyt lainkaan ensikertalaisuustietoja haulle $hakuOid"
          )
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
          val result = formEnsikertalainenSiirtotiedostoForHaku(hakuOid)
          Await.result(result, 45.minutes)
          logger.info(
            s"($executionId) Valmista haulle $hakuOid, kesto ${System.currentTimeMillis() - start} ms"
          )
          val totalProcessed = results.updateAndGet(r => (hakuOid, None) :: r)
          logger.info(
            s"Valmiina ${totalProcessed.size} / ${hakuOids.size}, onnistuneita ${totalProcessed
              .count(_._2.isEmpty)} ja epäonnistuneita ${totalProcessed.count(_._2.nonEmpty)}"
          )
        } catch {
          case t: Throwable =>
            logger
              .error(
                s"($executionId) (kesto ${System.currentTimeMillis() - start} ms) Siirtotiedoston muodostaminen haun $hakuOid ensikertalaisista epäonnistui:",
                t
              )
            val totalProcessed = results.updateAndGet(r => (hakuOid, Some(t)) :: r)
            logger.info(
              s"Valmiina ${totalProcessed.size} / ${hakuOids.size}, onnistuneita ${totalProcessed
                .count(_._2.isEmpty)} ja epäonnistuneita ${totalProcessed.count(_._2.nonEmpty)}"
            )
        }
      })
    val failed = results.get().filter(_._2.isDefined)
    failed.foreach(result =>
      logger.error(
        s"Ei saatu muodostettua ensikertalaisten siirtotiedostoa haulle ${result._1}: ${result._2}"
      )
    )
    s"Onnistuneita ${hakuOids.size - failed.size}, epäonnistuneita ${failed.size}"
  }

  def muodostaSeuraavaSiirtotiedosto = {
    val executionId = UUID.randomUUID().toString
    val latestProcessInfo: Option[SiirtotiedostoProcess] =
      db.getLatestProcessInfo
    logger.info(s"Haettiin tieto edellisestä siirtotiedostoprosessista: $latestProcessInfo")

    val windowStart = latestProcessInfo match {
      case Some(processInfo) if processInfo.finishedSuccessfully => processInfo.windowEnd
      case Some(processInfo)                                     => processInfo.windowStart //retry previous
      case None                                                  => 0
    }

    val windowEnd = System.currentTimeMillis()

    val newProcessInfo: SiirtotiedostoProcess =
      db.createNewProcess(executionId, windowStart, windowEnd)
        .getOrElse(throw new RuntimeException("Siirtotiedosto process does not exist!"))
    logger.info(s"Luotiin ja persistoitiin tieto luodusta: $newProcessInfo")

    try {
      if (OvaraUtil.shouldFormEnsikertalaiset()) {
        logger.info(s"${newProcessInfo.executionId} Muodostetaan ensikertalaisuudet")
        triggerEnsikertalaiset(true)
      }

      val processResult: SiirtotiedostoProcess = formSiirtotiedostotPaged(
        newProcessInfo
      )

      logger.info(s"Siirtotiedostojen muodostus valmistui, persistoidaan tulokset: $processResult")
      db.persistFinishedProcess(processResult)
      processResult
    } catch {
      case t: Throwable =>
        logger.error(
          s"Virhe siirtotiedoston muodostamisessa tai persistoinnissa, merkitään virhe kantaan...",
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

  override def formEnsikertalainenSiirtotiedostoForHakus(hakuOids: Seq[String]): String = ???

  override def muodostaSeuraavaSiirtotiedosto(): SiirtotiedostoProcess = ???
}
