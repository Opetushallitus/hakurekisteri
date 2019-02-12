package fi.vm.sade.hakurekisteri.web.koski

import fi.vm.sade.hakurekisteri.web.rest.support.ModelResponseMessage
import org.scalatra.swagger.SwaggerSupport

trait KoskiImporterSwaggerApi extends SwaggerSupport {
  val read = apiOperation[Boolean]("paivitaOpiskelijaKoskesta")
    .summary("Päivittää annetun oppijan tiedot koskesta")
    .notes("Palauttaa true jos päivitys onnistui, muutoin false")
    .parameter(pathParam[String]("oppijaOid")
      .description("oppijanumero").required)
    .parameter(queryParam[Option[Boolean]]("haelukio").description("Haetaanko koskesta myös lukio-opiskelijoiden arvosanat").optional.defaultValue(Some(false)))
    .parameter(queryParam[Option[Boolean]]("haeammatilliset").description("Haetaanko koskesta myös ammatillisten opiskelijoiden arvosanat").optional.defaultValue(Some(false)))
    .responseMessage(ModelResponseMessage(400, "[invalid parameter description]"))
    .responseMessage(ModelResponseMessage(404, "oppija not found"))
    .responseMessage(ModelResponseMessage(500, "error in service"))

  val updateHenkilot = apiOperation[Boolean]("paivitaOpiskelijatKoskestaHenkiloOideille")
    .summary("Päivittää annetun oppijalistan tiedot koskesta")
    .notes("Palauttaa true jos päivitys onnistui, muutoin false")
    .parameter(bodyParam[String]("oppijaoids")
      .description("Lista oppijanumeroita").required)
    .parameter(queryParam[Option[Boolean]]("haelukio").description("Haetaanko koskesta myös lukio-opiskelijoiden arvosanat").optional.defaultValue(Some(false)))
    .parameter(queryParam[Option[Boolean]]("haeammatilliset").description("Haetaanko koskesta myös ammatillisten opiskelijoiden arvosanat").optional.defaultValue(Some(false)))
    .responseMessage(ModelResponseMessage(400, "[invalid parameter description]"))
    .responseMessage(ModelResponseMessage(404, "oppija not found"))
    .responseMessage(ModelResponseMessage(500, "error in service"))

  val updateForHaku = apiOperation[Boolean]("paivitaOpiskelijatKoskestaHaulle")
    .summary("Päivittää haun hakijoiden tiedot koskesta")
    .notes("Palauttaa true jos päivitys onnistui, muutoin false")
    .parameter(pathParam[String]("hakuOid")
      .description("haun oid").required)
    .parameter(queryParam[Option[Boolean]]("haelukio").description("Haetaanko koskesta myös lukio-opiskelijoiden arvosanat").optional.defaultValue(Some(false)))
    .parameter(queryParam[Option[Boolean]]("haeammatilliset").description("Haetaanko koskesta myös ammatillisten opiskelijoiden arvosanat").optional.defaultValue(Some(false)))
    .responseMessage(ModelResponseMessage(400, "[invalid parameter description]"))
    .responseMessage(ModelResponseMessage(404, "oppija not found"))
    .responseMessage(ModelResponseMessage(500, "error in service"))

}
