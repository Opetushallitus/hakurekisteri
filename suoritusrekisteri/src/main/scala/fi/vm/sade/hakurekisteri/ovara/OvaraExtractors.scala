package fi.vm.sade.hakurekisteri.ovara

import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import slick.jdbc.GetResult

import org.json4s._
import org.json4s.jackson.JsonMethods._

trait OvaraExtractors extends HakurekisteriJsonSupport {
  implicit val formats: DefaultFormats.type = org.json4s.DefaultFormats

  protected implicit val getSiirtotiedostoProcessInfoResult: GetResult[SiirtotiedostoProcess] =
    GetResult(r =>
      SiirtotiedostoProcess(
        id = r.nextLong(),
        executionId = r.nextString(),
        windowStart = r.nextLong(),
        windowEnd = r.nextLong(),
        runStart = r.nextString(),
        runEnd = r.nextStringOption(),
        info = r
          .nextStringOption()
          .map(parse(_).extract[SiirtotiedostoProcessInfo])
          .getOrElse(SiirtotiedostoProcessInfo(Map.empty)),
        finishedSuccessfully = r.nextBoolean(),
        errorMessage = r.nextStringOption()
      )
    )

  protected implicit val getSiirtotiedostoSuoritusResult: GetResult[SiirtotiedostoSuoritus] =
    GetResult(r =>
      SiirtotiedostoSuoritus(
        resourceId = r.nextString(),
        komo = r.nextStringOption(),
        myontaja = r.nextString(),
        tila = r.nextStringOption(),
        valmistuminen = r.nextStringOption(),
        henkiloOid = r.nextString(),
        yksilollistaminen = r.nextStringOption(),
        suoritusKieli = r.nextStringOption(),
        inserted = r.nextLong(),
        deleted = r.nextBooleanOption(),
        source = r.nextString(),
        kuvaus = r.nextStringOption(),
        vuosi = r.nextStringOption(),
        tyyppi = r.nextStringOption(),
        index = r.nextStringOption(),
        vahvistettu = r.nextBoolean(),
        lahdeArvot = parse(r.nextString).extract[Map[String, String]]
      )
    )

  protected implicit val getSiirtotiedostoArvosanaResult: GetResult[SiirtotiedostoArvosana] =
    GetResult(r =>
      SiirtotiedostoArvosana(
        resourceId = r.nextString(),
        suoritus = r.nextString(),
        arvosana = r.nextStringOption(),
        asteikko = r.nextStringOption(),
        aine = r.nextStringOption(),
        lisatieto = r.nextStringOption(),
        valinnainen = r.nextBoolean(),
        inserted = r.nextLong(),
        deleted = r.nextBoolean(),
        pisteet = r.nextStringOption(),
        myonnetty = r.nextStringOption(),
        source = r.nextString(),
        jarjestys = r.nextStringOption(),
        lahdeArvot = parse(r.nextString).extract[Map[String, String]]
      )
    )

  protected implicit val getSiirtotiedostoOpiskelijaResult: GetResult[SiirtotiedostoOpiskelija] =
    GetResult(r =>
      SiirtotiedostoOpiskelija(
        resourceId = r.nextString(),
        oppilaitosOid = r.nextString(),
        luokkataso = r.nextString(),
        luokka = r.nextString(),
        henkiloOid = r.nextString(),
        alkuPaiva = r.nextLong(),
        loppuPaiva = r.nextLongOption(),
        inserted = r.nextLong(),
        deleted = r.nextBoolean(),
        source = r.nextString()
      )
    )

  protected implicit val getSiirtotiedostoOpiskeluoikeusResult
    : GetResult[SiirtotiedostoOpiskeluoikeus] =
    GetResult(r =>
      SiirtotiedostoOpiskeluoikeus(
        resourceId = r.nextString(),
        alkuPaiva = r.nextLong(),
        loppuPaiva = r.nextLongOption(),
        henkiloOid = r.nextString(),
        komo = r.nextString(),
        myontaja = r.nextString(),
        source = r.nextString(),
        inserted = r.nextLong(),
        deleted = r.nextBoolean()
      )
    )
}
