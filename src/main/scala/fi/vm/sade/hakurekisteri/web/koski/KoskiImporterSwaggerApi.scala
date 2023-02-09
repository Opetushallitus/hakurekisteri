package fi.vm.sade.hakurekisteri.web.koski

import fi.vm.sade.hakurekisteri.{Config}
import fi.vm.sade.hakurekisteri.web.rest.support.ModelResponseMessage
import org.scalatra.swagger.SwaggerSupport

import scala.util.Try

trait KoskiImporterSwaggerApi extends SwaggerSupport {
  val maxOppijatPostSize: Int = Try(Config.globalConfig.integrations.koskiMaxOppijatPostSize)
    .getOrElse(Config.mockDevConfig.integrations.koskiMaxOppijatPostSize)

  val read = apiOperation[Boolean]("paivitaOpiskelijaKoskesta")
    .summary("Päivittää annetun oppijan tiedot koskesta")
    .description("Palauttaa true jos päivitys onnistui, muutoin false")
    .parameter(
      pathParam[String]("oppijaOid")
        .description("oppijanumero")
        .required
    )
    .parameter(
      queryParam[Option[Boolean]]("haelukio")
        .description("Haetaanko koskesta myös lukio-opiskelijoiden arvosanat")
        .optional
        .defaultValue(Some(false))
    )
    .parameter(
      queryParam[Option[Boolean]]("haeammatilliset")
        .description("Haetaanko koskesta myös ammatillisten opiskelijoiden arvosanat")
        .optional
        .defaultValue(Some(false))
    )
    .responseMessage(ModelResponseMessage(400, "[invalid parameter description]"))
    .responseMessage(ModelResponseMessage(404, "oppija not found"))
    .responseMessage(ModelResponseMessage(500, "error in service"))
    .tags("koskiimporter")

  val updateHenkilot = apiOperation[Boolean]("paivitaOpiskelijaListaKoskesta")
    .summary("Päivittää annetun oppijalistan tiedot koskesta")
    .description("Palauttaa true jos päivitys onnistui, muutoin false")
    .parameter(
      bodyParam[String]("oppijaoids")
        .description(
          s"""lista oppijanumeroista (max ${maxOppijatPostSize} kpl), esim ["1.2.246.562.24.00000000001", "1.2.246.562.24.00000000002"]"""
        )
        .required
    )
    .parameter(
      queryParam[Option[Boolean]]("haelukio")
        .description("Haetaanko koskesta myös lukio-opiskelijoiden arvosanat")
        .optional
        .defaultValue(Some(false))
    )
    .parameter(
      queryParam[Option[Boolean]]("haeammatilliset")
        .description("Haetaanko koskesta myös ammatillisten opiskelijoiden arvosanat")
        .optional
        .defaultValue(Some(false))
    )
    .responseMessage(ModelResponseMessage(400, "[invalid parameter description]"))
    .responseMessage(ModelResponseMessage(404, "oppija not found"))
    .responseMessage(ModelResponseMessage(500, "error in service"))
    .tags("koskiimporter")

  val updateForHaku = apiOperation[Boolean]("paivitaOpiskelijatKoskestaHaulle")
    .summary("Päivittää haun hakijoiden tiedot koskesta")
    .description("Palauttaa true jos päivitys onnistui, muutoin false")
    .parameter(
      pathParam[String]("hakuOid")
        .description("haun oid")
        .required
    )
    .parameter(
      queryParam[Option[Boolean]]("haelukio")
        .description("Haetaanko koskesta myös lukio-opiskelijoiden arvosanat")
        .optional
        .defaultValue(Some(false))
    )
    .parameter(
      queryParam[Option[Boolean]]("haeammatilliset")
        .description("Haetaanko koskesta myös ammatillisten opiskelijoiden arvosanat")
        .optional
        .defaultValue(Some(false))
    )
    .parameter(
      queryParam[Option[Boolean]]("haeseiskakasijavalmistava")
        .description(
          "Haetaanko koskesta myös 7.- ja 8.-luokkalaisten ja perusopetukseen valmistavan opetuksen opiskelijoiden tiedot"
        )
        .optional
        .defaultValue(Some(false))
    )
    .responseMessage(ModelResponseMessage(400, "[invalid parameter description]"))
    .responseMessage(ModelResponseMessage(404, "oppija not found"))
    .responseMessage(ModelResponseMessage(500, "error in service"))
    .tags("koskiimporter")

}
