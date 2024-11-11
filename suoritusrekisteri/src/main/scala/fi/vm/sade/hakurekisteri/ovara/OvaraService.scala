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

  implicit val ec: ExecutionContext = ExecutorUtil.createExecutor(10, "ovara-executor")

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

  def getHarkinnanvaraisuusResults(
    executionId: String,
    hakuOids: Seq[String]
  ): Future[Seq[SiirtotiedostoResultForHaku]] = {
    implicit val ec: ExecutionContext = ExecutorUtil.createExecutor(
      2,
      "harkinnanvaraisuus-executor"
    )
    val result = Future.sequence(
      hakuOids.map(hakuOid =>
        processHakuForHarkinnanvaraisuus(executionId, hakuOid).recoverWith { case e: Exception =>
          logger.error(
            s"${Thread.currentThread().getName} harkinnanvaraisuudet epäonnistui haulle $hakuOid",
            e
          )
          Future.successful(
            SiirtotiedostoResultForHaku(hakuOid, "harkinnanvaraisuus", 0, Some(e))
          )
        }
      )
    )
    result.onComplete(res =>
      logger.info(s"${Thread.currentThread().getName} Valmista (harkinnanvaraisuudet)! $res")
    )
    result
  }

  def getProxysuorituksetResults(
    executionId: String,
    hakuOids: Seq[String]
  ): Future[Seq[SiirtotiedostoResultForHaku]] = {
    implicit val ec: ExecutionContext = ExecutorUtil.createExecutor(
      2,
      "proxysuoritukset-executor"
    )
    val valmiitaProxyHakuja = new AtomicReference[Int](0)
    val proxySuorituksetGroups =
      hakuOids.grouped((hakuOids.size / 4) + 1).toSeq
    val proxySuorituksetFutures = proxySuorituksetGroups.map(pGroup => {
      pGroup.foldLeft(Future.successful(Seq[SiirtotiedostoResultForHaku]())) {
        case (accResult, hakuOid) =>
          accResult.flatMap((r: Seq[SiirtotiedostoResultForHaku]) => {
            logger.info(
              s"${Thread.currentThread().getName} Valmiita proxyhakuja ${valmiitaProxyHakuja
                .updateAndGet(n => n + 1)}/${hakuOids.size}"
            )
            processHakuForProxysuoritukset(executionId, hakuOid)
              .recoverWith { case e: Exception =>
                logger.error(
                  s"${Thread.currentThread().getName} proxysuoritukset epäonnistui haulle $hakuOid",
                  e
                )
                Future.successful(
                  SiirtotiedostoResultForHaku(hakuOid, "proxysuoritukset", 0, Some(e))
                )
              }
              .map(psResult => {
                r :+ psResult
              })
          })
      }
    })
    val result = Future.sequence(proxySuorituksetFutures).map(_.flatten)
    result.onComplete(res =>
      logger.info(s"${Thread.currentThread().getName} Valmista (proxysuoritukset)! $res")
    )
    result
  }

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

      val hautForEnsikertalaisuus = if (params.ensikertalaisuudet) {
        haut
          .filter(haku => (!params.vainAktiiviset || haku.isActive) && haku.kkHaku)
          .map(_.oid)
          .toSet
      } else Set[String]()
      logger.info(
        s"Käsitellään ${hautForHarkinnanvaraisuus.size} haun harkinnanvaraisuudet, ${hautForProxysuoritukset.size} haun proxySuoritukset ja ${hautForEnsikertalaisuus.size} haun harkinnanvaraisuudet"
      )

      val harkinnanvaraisuusF =
        getHarkinnanvaraisuusResults(params.executionId, hautForHarkinnanvaraisuus.toSeq)
      val proxysuoritusF =
        getProxysuorituksetResults(params.executionId, hautForProxysuoritukset.toSeq)
      val ensikertalaisetResults = formEnsikertalainenSiirtotiedostoForHakus(
        hautForEnsikertalaisuus
      )

      val combinedResults = for {
        harkinnanvaraisuusResults <- harkinnanvaraisuusF
        proxysuorituksetResults <- proxysuoritusF
      } yield {
        harkinnanvaraisuusResults ++ proxysuorituksetResults ++ ensikertalaisetResults
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

  val GROUP_SIZE_PROXY = 100
  def processHakuForProxysuoritukset(executionId: String, hakuOid: String)(implicit
    ec: ExecutionContext
  ): Future[SiirtotiedostoResultForHaku] = {
    try {
      val fileCounter = new AtomicReference[Int](1)
      val hakijat = hakemusService
        .ataruhakemustenHenkilot(
          AtaruHenkiloSearchParams(hakuOid = Some(hakuOid), hakukohdeOids = None)
        )

      val countF = hakijat.flatMap(h => {
        logger.info(
          s"$executionId Saatiin ${h.size} hakijaa haulle $hakuOid, haetaan proxysuoritukset"
        )
        val erat = h.grouped(GROUP_SIZE_PROXY).toSeq

        erat.foldLeft(Future.successful(0)) { case (accResult, chunk) =>
          accResult.flatMap(rs => {
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
              .map(proxySuoritukset => {
                logger.info(
                  s"$executionId Saatiin ${proxySuoritukset.size} proxysuoritusta haun $hakuOid, hakijalle, erä ${fileCounter
                    .get()}/${erat.size}. Tallennetaan siirtotiedosto."
                )
                s3Client
                  .saveSiirtotiedosto[SiirtotiedostoProxySuoritukset](
                    "proxysuoritukset",
                    proxySuoritukset,
                    executionId,
                    fileCounter.getAndUpdate(n => n + 1),
                    Some(hakuOid)
                  )
                rs + proxySuoritukset.size
              })
          })
        }
      })
      countF.map(count => SiirtotiedostoResultForHaku(hakuOid, "proxysuoritukset", count, None))
    } catch {
      case t: Throwable =>
        logger.error(s"Jotain meni yllättävän paljon vikaan", t)
        Future.successful(SiirtotiedostoResultForHaku(hakuOid, "proxysuoritukset", 0, Some(t)))
    }
  }

  val GROUP_SIZE =
    100 //Tämä on varmaan naurettavan pieni, mutta suoritusaika vaihtelee valtavasti ja suuremmista määristä seuraa timeouteja.
  def processHakuForHarkinnanvaraisuus(executionId: String, hakuOid: String)(implicit
    ec: ExecutionContext
  ): Future[SiirtotiedostoResultForHaku] = {
    try {
      val fileCounter = new AtomicReference[Int](1)
      val hakijat = hakemusService
        .ataruhakemustenHenkilot(
          AtaruHenkiloSearchParams(hakuOid = Some(hakuOid), hakukohdeOids = None)
        )

      val countF = hakijat.flatMap(h => {
        logger.info(
          s"${Thread.currentThread().getName} $executionId Saatiin ${h.size} hakijaa haulle $hakuOid, haetaan harkinnanvaraisuudet. "
        )
        val erat = h.grouped(GROUP_SIZE).toSeq

        erat.foldLeft(Future.successful(0)) { case (accResult, chunk) =>
          accResult.flatMap(rs => {
            koosteService
              .getHarkinnanvaraisuudetForHakemusOids(chunk.map(_.oid))
              .recoverWith { case e: Exception =>
                logger.error(
                  s"${Thread.currentThread().getName} erä ${fileCounter.get()}/${erat.size} harkinnanvaraisuudet epäonnistui haulle $hakuOid, yritetään kerran uudelleen",
                  e
                )
                koosteService
                  .getHarkinnanvaraisuudetForHakemusOids(chunk.map(_.oid))
              }
              .map(harkinnanvaraisuudet => {
                logger.info(
                  s"${Thread.currentThread().getName} $executionId Saatiin ${harkinnanvaraisuudet.size} harkinnanvaraisuutta haun $hakuOid, hakijalle, erä ${fileCounter
                    .get()}/${erat.size}. Tallennetaan siirtotiedosto."
                )
                s3Client
                  .saveSiirtotiedosto[HakemuksenHarkinnanvaraisuus](
                    "harkinnanvaraisuus",
                    harkinnanvaraisuudet,
                    executionId,
                    fileCounter.getAndUpdate(n => n + 1),
                    Some(hakuOid)
                  )
                rs + harkinnanvaraisuudet.size
              })
          })
        }
      })
      countF.map(count => SiirtotiedostoResultForHaku(hakuOid, "harkinnanvaraisuus", count, None))
    } catch {
      case t: Throwable =>
        logger.error(s"${Thread.currentThread().getName} Jotain meni yllättävän paljon vikaan", t)
        Future.successful(SiirtotiedostoResultForHaku(hakuOid, "harkinnanvaraisuus", 0, Some(t)))
    }
  }

  //Ensivaiheessa ajetaan tämä kaikille kk-hauille kerran, myöhemmin riittää synkata kerran päivässä aktiivisten kk-hakujen tiedot
  def formEnsikertalainenSiirtotiedostoForHakus(
    hakuOids: Set[String]
  ): Seq[SiirtotiedostoResultForHaku] = {
    implicit val ec: ExecutionContext = ExecutorUtil.createExecutor(
      4,
      "ensikertalaisuudet-executor"
    )
    val executionId = UUID.randomUUID().toString
    val fileCounter = new AtomicReference[Int](0)
    val results = new AtomicReference[List[SiirtotiedostoResultForHaku]](List.empty)
    def formEnsikertalainenSiirtotiedostoForHaku(hakuOid: String): Future[(String, Int)] = {
      implicit val to: Timeout = Timeout(30.minutes)
      logger.info(
        s"${Thread.currentThread().getName} $executionId Ei löytynyt lainkaan ensikertalaisuustietoja haulle $hakuOid"
      )
      val ensikertalaiset: Future[Seq[Ensikertalainen]] =
        (ensikertalainenActor ? HaunEnsikertalaisetQuery(hakuOid)).mapTo[Seq[Ensikertalainen]]
      ensikertalaiset.map((rawEnsikertalaiset: Seq[Ensikertalainen]) => {
        logger.info(
          s"${Thread.currentThread().getName} $executionId Saatiin ${rawEnsikertalaiset.size} ensikertalaisuustietoa haulle $hakuOid. Tallennetaan siirtotiedosto."
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
            s"${Thread.currentThread().getName} ($executionId) Ei löytynyt lainkaan ensikertalaisuustietoja haulle $hakuOid"
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
            s"${Thread.currentThread().getName} ($executionId) Valmista haulle $hakuOid, kesto ${System
              .currentTimeMillis() - start} ms"
          )
          val totalProcessed =
            results.updateAndGet(r =>
              SiirtotiedostoResultForHaku(result._1, "ensikertalaisuus", result._2, None) :: r
            )
          logger.info(
            s"${Thread.currentThread().getName} Valmiina ${totalProcessed.size} / ${hakuOids.size}, onnistuneita ${totalProcessed
              .count(_.error.isEmpty)} ja epäonnistuneita ${totalProcessed.count(_.error.nonEmpty)}"
          )
        } catch {
          case t: Throwable =>
            logger
              .error(
                s"${Thread.currentThread().getName} ($executionId) (kesto ${System
                  .currentTimeMillis() - start} ms) Siirtotiedoston muodostaminen haun $hakuOid ensikertalaisista epäonnistui:",
                t
              )
            val totalProcessed =
              results.updateAndGet(r =>
                SiirtotiedostoResultForHaku(hakuOid, "ensikertalaisuus", 0, Some(t)) :: r
              )
            logger.info(
              s"${Thread.currentThread().getName} $executionId Valmiina ${totalProcessed.size} / ${hakuOids.size}, onnistuneita ${totalProcessed
                .count(_.error.isEmpty)} ja epäonnistuneita ${totalProcessed.count(_.error.nonEmpty)}"
            )
        }
      })
    val finalResults = results.get()
    val failed = finalResults.filter(_.error.isDefined)
    failed.foreach(result =>
      logger.error(
        s"${Thread.currentThread().getName} Ei saatu muodostettua ensikertalaisten siirtotiedostoa haulle ${result.hakuOid}: ${result.error}"
      )
    )
    logger.info(
      s"${Thread.currentThread().getName} Ensikertalaisuudet muodostettu! Onnistuneita hakuja ${hakuOids.size - failed.size}, epäonnistuneita ${failed.size}"
    )
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
