package fi.vm.sade.hakurekisteri.web.koski

import fi.vm.sade.hakurekisteri.web.rest.support.ModelResponseMessage
import org.scalatra.swagger.SwaggerSupport

trait KoskiImporterSwaggerApi extends SwaggerSupport {
  val read = apiOperation[Boolean]("paivitaOpiskelijaKoskesta")
    .summary("Päivittää annetun oppijan tiedot koskesta")
    .notes("Palauttaa true jos päivitys onnistui, muutoin false")
    .parameter(pathParam[String]("oppijaOid")
      .description("oppijanumero").required)
    .responseMessage(ModelResponseMessage(400, "[invalid parameter description]"))
    .responseMessage(ModelResponseMessage(404, "oppija not found"))
    .responseMessage(ModelResponseMessage(500, "error in service"))

}
