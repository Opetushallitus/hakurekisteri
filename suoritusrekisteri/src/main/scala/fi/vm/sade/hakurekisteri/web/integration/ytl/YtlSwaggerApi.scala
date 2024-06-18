package fi.vm.sade.hakurekisteri.web.integration.ytl

import org.scalatra.swagger.SwaggerSupport

trait YtlSwaggerApi extends SwaggerSupport {

  val syncPerson = apiOperation[Any]("status")
    .summary("Päivittää yhden henkilön YTL-tiedot Suoritusrekisteriin.")
    .description("Päivittää yhden henkilön YTL-tiedot Suoritusrekisteriin.")
    .parameter(
      pathParam[String]("personOid")
        .description("personOid")
        .required
    )
    .tags("Ytl-resource")

  val syncHaku = apiOperation[Any]("paivitaHaunOpiskelijatYTLTiedot")
    .summary("Päivittää haun henkilöiden YTL-tiedot Suoritusrekisteriin.")
    .description("Päivittää haunt YTL-tiedot Suoritusrekisteriin.")
    .parameter(
      pathParam[String]("hakuOid")
        .description("hakuOid")
        .required
    )
    .tags("Ytl-resource")

  val syncPersons = apiOperation[Any]("paivitaOpiskelijatYTLTiedot")
    .summary("Päivittää annetun oppijalistan tiedot YTl:stä Suoritusrekisteriin")
    .description("Päivittää annetun oppijalistan tiedot YTl:stä Suoritusrekisteriin")
    .parameter(
      bodyParam[String]("oppijaoids")
        .description(
          s"""lista oppijanumeroista (esim ["1.2.246.562.24.00000000001", "1.2.246.562.24.00000000002"]"""
        )
        .required
    )
    .tags("Ytl-resource")

  val syncAll = apiOperation[Any]("paivitaKaikkiYTLTiedot")
    .summary("Päivittää aktiivisten kk-hakujen hakijoiden tiedot YTl:stä Suoritusrekisteriin")
    .description("Päivittää aktiivisten kk-hakujen hakijoiden tiedot YTl:stä Suoritusrekisteriin")
    .tags("Ytl-resource")
}
