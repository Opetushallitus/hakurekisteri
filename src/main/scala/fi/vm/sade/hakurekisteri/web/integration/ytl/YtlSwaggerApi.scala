package fi.vm.sade.hakurekisteri.web.integration.ytl

import fi.vm.sade.hakurekisteri.web.integration.virta.VirtaSwaggerModel
import fi.vm.sade.hakurekisteri.web.rest.support.{ModelResponseMessage, OldSwaggerSyntax}
import org.scalatra.AsyncResult
import org.scalatra.swagger.{DataType, Model, SwaggerSupport}

trait YtlSwaggerApi extends SwaggerSupport with YtlSwaggerModel {

  val syncPerson = apiOperation[Any]("status")
    .summary("Päivittää yhden henkilön YTL-tiedot Suoritusrekisteriin.")
    .description("Päivittää yhden henkilön YTL-tiedot Suoritusrekisteriin.")
    .parameter(pathParam[String]("personOid")
      .description("personOid").required)
    .tags("Ytl-resource")
}

trait YtlSwaggerModel {

}