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
import fi.vm.sade.hakurekisteri.integration.haku.{
  AllHaut,
  GetHautQuery,
  Haku,
  HakuRequest,
  RestHaku,
  RestHakuResult
}
import fi.vm.sade.hakurekisteri.integration.kouta.KoutaInternalActorRef
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.utils.slf4j.Logging

import java.util.UUID
import scala.annotation.tailrec
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.DurationInt

trait IOvaraService {
  //Muodostetaan siirtotiedostot kaikille neljälle tyypille. Jos dataa on aikavälillä paljon, muodostuu useita tiedostoja per tyyppi.
  //Tiedostot tallennetaan s3:seen.
  def formSiirtotiedostotPaged(start: Long, end: Long): Map[String, Long]
  def formEnsikertalainenSiirtotiedostoForHakus(hakuOids: Seq[String])
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
    2,
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
    val allHaut: Future[AllHaut] =
      (hakuActor ? HakuRequest)
        .mapTo[AllHaut] //fixme, tää on ongelma koska palautuu 0 hakua jos kaikkia hakuja ei oo vielä haettu actorin päässä.
    val hakuResult = Await.result(allHaut, 5.minutes)
    val kiinnostavat =
      hakuResult.haut.filter(haku => (!vainAktiiviset || haku.isActive) && haku.kkHaku).map(_.oid)
    logger.info(
      s"Löydettiin ${kiinnostavat.size} kiinnostavaa hakua yhteensä ${hakuResult.haut.size} hausta. Vain aktiiviset: $vainAktiiviset"
    )
    formEnsikertalainenSiirtotiedostoForHakus(kiinnostavat)
  }

  //Ensivaiheessa ajetaan tämä kaikille kk-hauille kerran, myöhemmin riittää synkata kerran päivässä aktiivisten kk-hakujen tiedot
  def formEnsikertalainenSiirtotiedostoForHakus(hakuOids: Seq[String]) = {
    val executionId = UUID.randomUUID().toString
    var fileNumber = 1
    def formSiirtotiedostoForHaku(hakuOid: String) = {
      implicit val to: Timeout = Timeout(30.minutes)

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
        s3Client
          .saveSiirtotiedosto[SiirtotiedostoEnsikertalainen](
            "ensikertalainen",
            ensikertalaiset,
            executionId,
            fileNumber
          )
      })
    }

    val infoStr =
      if (hakuOids.size <= 5) s"hauille ${hakuOids.toString}" else s"${hakuOids.size} haulle."
    logger.info(s"($executionId) Muodostetaan siirtotiedostot $infoStr")
    val resultsByHaku = hakuOids.map(hakuOid => {
      try {
        val result = formSiirtotiedostoForHaku(hakuOid)
        //Todo, muu toteutus tälle? Mikä on riittävä timeout, mitä jos jäädään jumiin? Käsiteltäviä hakuja voi olla paljon,
        //kaikkea ei voi tehdä rinnakkain. Muutaman kerrallaan varmaan voisi.
        Await.result(result, 45.minutes)
        logger.info(s"($executionId) Valmista haulle $hakuOid")
        fileNumber += 1
        (hakuOid, None)
      } catch {
        case t: Throwable =>
          logger
            .error(
              s"($executionId) Siirtotiedoston muodostaminen haun $hakuOid ensikertalaisista epäonnistui:",
              t
            )
          (
            hakuOid,
            Some(t.getMessage)
          ) //Todo, retry? Voisi olla järkevää, jos muodostetaan siirtotiedosto kymmenille hauille ja yksi satunnaissepäonnistuu.
      }

    })
    val failed = resultsByHaku.filter(_._2.isDefined)
    logger.error(s"($executionId) Failed: $failed")
    s"Onnistuneita ${hakuOids.size - failed.size}, epäonnistuneita ${failed.size}"
  }

  def formSiirtotiedostotPaged(start: Long, end: Long): Map[String, Long] = {
    //lukitaan aikaikkunan loppuhetki korkeintaan nykyhetkeen, jolloin ei tarvitse huolehtia tämän jälkeen kantaan mahdollisesti tulevista muutoksista,
    //ja eri tyyppiset tiedostot muodostetaan samalle aikaikkunalle.
    val executionId = UUID.randomUUID().toString
    logger.info(s"($executionId) Muodostetaan siirtotiedosto, $start $end")
    val baseParams =
      SiirtotiedostoPagingParams(
        executionId,
        1,
        "",
        start,
        math.min(System.currentTimeMillis(), end),
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
    logger.info(s"($executionId) Siirtotiedostot muodostettu, tuloksia: $resultCounts")
    resultCounts
  }

}

class OvaraServiceMock extends IOvaraService {
  override def formSiirtotiedostotPaged(start: Long, end: Long) = ???

  override def formEnsikertalainenSiirtotiedostoForHakus(hakuOids: Seq[String]): Unit = ???
}
