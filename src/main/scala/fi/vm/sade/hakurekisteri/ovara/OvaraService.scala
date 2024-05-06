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
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.utils.slf4j.Logging

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

  case class SiirtotiedostoEnsikertalainen(
    hakuOid: String,
    henkiloOid: String,
    isEnsikertalainen: Boolean,
    menettamisenPeruste: Option[MenettamisenPeruste]
  )

  //Ensivaiheessa ajetaan tämä kaikille kk-hauille kerran, myöhemmin riittää synkata kerran päivässä aktiivisten kk-hakujen tiedot
  def formEnsikertalainenSiirtotiedostoForHakus(hakuOids: Seq[String]) = {
    def formSiirtotiedostoForHaku(hakuOid: String) = {
      implicit val to: Timeout = Timeout(30.minutes)

      val ensikertalaiset: Future[Seq[Ensikertalainen]] =
        (ensikertalainenActor ? HaunEnsikertalaisetQuery(hakuOid)).mapTo[Seq[Ensikertalainen]]
      ensikertalaiset.map((rawEnsikertalaiset: Seq[Ensikertalainen]) => {
        logger.info(
          s"Saatiin ${rawEnsikertalaiset.size} ensikertalaisuustietoa haulle $hakuOid. Tallennetaan siirtotiedosto."
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
          .saveSiirtotiedosto[SiirtotiedostoEnsikertalainen]("ensikertalainen", ensikertalaiset)
      })
    }

    val infoStr =
      if (hakuOids.size <= 5) s"hauille ${hakuOids.toString}" else s"${hakuOids.size} haulle."
    logger.info(s"Muodostetaan siirtotiedostot $infoStr")
    val resultsByHaku = hakuOids.map(hakuOid => {
      try {
        val result = formSiirtotiedostoForHaku(hakuOid)
        //Todo, muu toteutus tälle? Mikä on riittävä timeout, mitä jos jäädään jumiin? Käsiteltäviä hakuja voi olla paljon,
        //kaikkea ei voi tehdä rinnakkain. Muutaman kerrallaan varmaan voisi.
        Await.result(result, 45.minutes)
        logger.info(s"Valmista haulle $hakuOid")
        (hakuOid, None)
      } catch {
        case t: Throwable =>
          logger
            .error(s"Siirtotiedoston muodostaminen haun $hakuOid ensikertalaisista epäonnistui:", t)
          (
            hakuOid,
            Some(t.getMessage)
          ) //Todo, retry? Voisi olla järkevää, jos muodostetaan siirtotiedosto kymmenille hauille ja yksi satunnaissepäonnistuu.
      }

    })
    val failed = resultsByHaku.filter(_._2.isDefined)
    logger.error(s"Failed: $failed")
    s"Onnistuneita ${hakuOids.size - failed.size}, epäonnistuneita ${failed.size}"
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

  override def formEnsikertalainenSiirtotiedostoForHakus(hakuOids: Seq[String]): Unit = ???
}
