package fi.vm.sade.hakurekisteri.ovara

import org.scalatra.swagger.SwaggerSupport

trait OvaraSwaggerApi extends SwaggerSupport {

  val muodostaAikavalille = apiOperation[Any]("muodostaSiirtotiedostoAikavalille")
    .summary("Muodostaa siirtotiedostot aikavälillä muuttuneista tiedostoista.")
    .description("Muodostaa siirtotiedostot aikavälillä muuttuneista tiedostoista.")
    .parameter(
      queryParam[Long]("start")
        .description("Aikavälin alkuhetki, esim 1731537749666")
        .defaultValue(1731587749666L)
    )
    .parameter(
      queryParam[Long]("end")
        .description("Aikavälin loppuhetki, esim 1731587968107L")
        .defaultValue(1731587968107L)
    )
    .tags("Ovara-resource")

  val muodostaPaivittaiset = apiOperation[Any]("muodostaPaivittaisetPaatellytSiirtotiedostot")
    .summary(
      "Muodostaa ovara-siirtotiedostot relevanttien hakujen ensikertalaisuuksille, proxysuoritustiedoille ja harkinnanvaraisuuksille."
    )
    .description(
      "Muodostaa ovara-siirtotiedostot relevanttien hakujen ensikertalaisuuksille, proxysuoritustiedoille ja harkinnanvaraisuuksille."
    )
    .parameter(
      queryParam[Boolean]("vainAktiiviset")
        .description("Käsitelläänkö vain aktiiviset haut")
    )
    .parameter(
      queryParam[Boolean]("ensikertalaisuudet")
        .description("Muodostetaanko ensikertalaisuudet")
    )
    .parameter(
      queryParam[Boolean]("harkinnanvaraisuudet")
        .description("Muodostetaanko harkinnanvaraisuudet")
    )
    .parameter(
      queryParam[Boolean]("proxySuoritukset")
        .description("Muodostetaanko proxySuoritukset")
    )
    .tags("Ovara-resource")
}
