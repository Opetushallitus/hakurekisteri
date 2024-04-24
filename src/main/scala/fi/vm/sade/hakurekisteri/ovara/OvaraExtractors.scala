package fi.vm.sade.hakurekisteri.ovara

import slick.jdbc.GetResult

trait OvaraExtractors {

  protected implicit val getSiirtotiedostoSuoritusResult: GetResult[SiirtotiedostoSuoritus] =
    GetResult(r =>
      SiirtotiedostoSuoritus(
        resourceId = r.nextString(),
        komo = r.nextString(),
        myontaja = r.nextString(),
        tila = r.nextString(),
        valmistuminen = r.nextString(),
        henkiloOid = r.nextString(),
        yksilollistaminen = r.nextString(),
        suoritusKieli = r.nextStringOption(),
        inserted = r.nextLong(),
        deleted = r.nextBooleanOption(),
        source = r.nextString(),
        kuvaus = r.nextStringOption(),
        vuosi = r.nextStringOption(),
        tyyppi = r.nextStringOption(),
        index = r.nextStringOption(),
        vahvistettu = r.nextBoolean(),
        lahdeArvot = Map("arvot" -> r.nextString()) //Todo, parsitaan kannan jsonb mapiksi
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
        lahdeArvot = Map("arvot" -> r.nextString()) //Todo, parsitaan kannan jsonb mapiksi
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
