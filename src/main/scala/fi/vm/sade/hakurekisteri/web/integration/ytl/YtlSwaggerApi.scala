package fi.vm.sade.hakurekisteri.web.integration.ytl

import org.scalatra.swagger.SwaggerSupport

trait YtlSwaggerApi extends SwaggerSupport {

  val syncPerson = apiOperation[Any]("status")
    .summary("Päivittää yhden henkilön YTL-tiedot Suoritusrekisteriin.")
    .description("Päivittää yhden henkilön YTL-tiedot Suoritusrekisteriin.")
    .parameter(pathParam[String]("personOid")
      .description("personOid").required)
    .tags("Ytl-resource")
}
