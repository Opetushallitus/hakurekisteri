package fi.vm.sade.hakurekisteri.ovara

import fi.vm.sade.utils.slf4j.Logging
import scala.annotation.tailrec

trait IOvaraService {
  //Muodostetaan siirtotiedostot kaikille neljälle tyypille. Jos dataa on aikavälillä paljon, muodostuu useita tiedostoja per tyyppi.
  //Tiedostot tallennetaan s3:seen.
  def formSiirtotiedostotPaged(start: Long, end: Long): Map[String, Long]
}

class OvaraService(db: OvaraDbRepository, s3Client: SiirtotiedostoClient, pageSize: Int)
    extends IOvaraService
    with Logging {

  @tailrec
  private def saveInSiirtotiedostoPaged[T](
    params: SiirtotiedostoPagingParams,
    pageFunction: SiirtotiedostoPagingParams => Seq[T]
  ): Long = {
    val pageResults = pageFunction(params)
    if (pageResults.isEmpty) {
      logger.info(
        s"Saatiin tyhjä sivu, lopetetaan. Haettiin yhteensä ${params.offset} kpl tyyppiä ${params.tyyppi}"
      )
      params.offset
    } else {
      logger.info(
        s"Saatiin sivu (${pageResults.size} kpl), haettu yhteensä ${params.offset + pageResults.size} kpl. Tallennetaan siirtotiedosto ennen seuraavan sivun hakemista. $params"
      )
      s3Client.saveSiirtotiedosto[T](params.tyyppi, pageResults)
      saveInSiirtotiedostoPaged(
        params.copy(offset = params.offset + pageResults.size),
        pageFunction
      )
    }
  }

  private def formSiirtotiedosto[T](
    params: SiirtotiedostoPagingParams,
    pageFunction: SiirtotiedostoPagingParams => Seq[T]
  ): Either[Throwable, Long] = {
    logger.info(s"Muodostetaan siirtotiedosto: $params")
    try {
      val count = saveInSiirtotiedostoPaged(params, pageFunction)
      Right(count)
    } catch {
      case t: Throwable =>
        logger.error(s"Virhe muodostettaessa siirtotiedostoa parametreilla $params:", t)
        Left(t)
    }
  }

  def formSiirtotiedostotPaged(start: Long, end: Long): Map[String, Long] = {
    //lukitaan aikaikkunan loppuhetki korkeintaan nykyhetkeen, jolloin ei tarvitse huolehtia tämän jälkeen kantaan mahdollisesti tulevista muutoksista,
    //ja eri tyyppiset tiedostot muodostetaan samalle aikaikkunalle.
    logger.info(s"Muodostetaan siirtotiedosto! $start $end")
    println(s"Muodostetaan siirtotiedosto! $start $end")
    val baseParams =
      SiirtotiedostoPagingParams("", start, math.min(System.currentTimeMillis(), end), 0, pageSize)

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
    logger.info(s"Siirtotiedostot muodostettu, tuloksia: $resultCounts")
    resultCounts
  }

}

class OvaraServiceMock extends IOvaraService {
  override def formSiirtotiedostotPaged(start: Long, end: Long) = ???
}
