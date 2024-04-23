package fi.vm.sade.hakurekisteri.ovara

import fi.vm.sade.utils.slf4j.Logging
import scala.annotation.tailrec

trait IOvaraService {
  //Muodostetaan siirtotiedostot kaikille neljälle tyypille. Jos dataa on aikavälillä paljon, muodostuu useita tiedostoja per tyyppi.
  //Tiedostot tallennetaan s3:seen.
  def formSiirtotiedostotPaged(start: Long, end: Long)
}

class OvaraService(db: OvaraDbRepository, s3Client: SiirtotiedostoClient, pageSize: Int)
    extends IOvaraService
    with Logging {

  @tailrec
  private def saveInSiirtotiedostoPaged[T](
    params: SiirtotiedostoPagingParams,
    pageFunction: SiirtotiedostoPagingParams => Seq[T]
  ): Option[Exception] = {
    val pageResults = pageFunction(params)
    if (pageResults.isEmpty) {
      None
    } else {
      logger.info(
        s"Saatiin sivu (${pageResults.size}) $params, tallennetaan siirtotiedosto ennen seuraavan sivun hakemista"
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
  ): Option[Throwable] = {
    logger.info(s"Muodostetaan siirtotiedosto: $params")
    try {
      saveInSiirtotiedostoPaged(params, pageFunction)
      None
    } catch {
      case t: Throwable =>
        logger.error(s"Virhe muodostettaessa siirtotiedostoa parametreilla $params:", t)
        Some(t)
    }
  }

  def formSiirtotiedostotPaged(start: Long, end: Long) = {
    //lukitaan aikaikkunan loppuhetki korkeintaan nykyhetkeen, jolloin ei tarvitse huolehtia tämän jälkeen kantaan mahdollisesti tulevista muutoksista,
    //ja eri tyyppiset tiedostot muodostetaan samalle aikaikkunalle.
    val baseParams =
      SiirtotiedostoPagingParams("", start, math.min(System.currentTimeMillis(), end), 0, pageSize)

    val suoritusException = formSiirtotiedosto[SiirtotiedostoSuoritus](
      baseParams.copy(tyyppi = "suoritus"),
      params => db.getChangedSuoritukset(params)
    )
    //todo arvosana, opiskelija, opiskeluoikeus
    Seq(suoritusException)
      .filter(_.isDefined)
      .foreach(t => {
        logger.error(s"Virhe siirtotiedoston muodostamisessa:", t)
      })
  }

}

class OvaraServiceMock extends IOvaraService {
  override def formSiirtotiedostotPaged(start: Long, end: Long) = ???
}
