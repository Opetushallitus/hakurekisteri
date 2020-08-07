package fi.vm.sade.hakurekisteri.web.integration.virta

import fi.vm.sade.hakurekisteri.integration.virta.VirtaOpintosuoritus
import fi.vm.sade.hakurekisteri.web.rest.support.ModelResponseMessage
import org.scalatra.swagger.SwaggerSupport

trait VirtaSuoritusSwaggerApi extends SwaggerSupport {

  val query = apiOperation[Seq[VirtaOpintosuoritus]]("haeSuoritukset")
    .summary("näyttää henkilön suoritukset")
    .description("Näyttää listauksen henkilön suorituksista annetun henkilötunnisteen perusteella")
    .parameter(pathParam[String]("id")
      .description("oppijanumero tai henkilötunnus").required)
    .responseMessage(ModelResponseMessage(400, "[invalid parameter description]"))
    .responseMessage(ModelResponseMessage(404, "person not found"))
    .responseMessage(ModelResponseMessage(500, "virta error"))
    .responseMessage(ModelResponseMessage(500, "back-end service timed out"))
    .responseMessage(ModelResponseMessage(500, "error in service"))
    .tags("virta")
}
