package fi.vm.sade.hakurekisteri.web.oppija

import org.scalatra.swagger.{DataType, SwaggerSupport}
import fi.vm.sade.hakurekisteri.web.arvosana.ArvosanaSwaggerModel
import fi.vm.sade.hakurekisteri.web.opiskelija.OpiskelijaSwaggerModel
import fi.vm.sade.hakurekisteri.web.opiskeluoikeus.OpiskeluoikeusSwaggerModel
import fi.vm.sade.hakurekisteri.web.suoritus.SuoritusSwaggerModel
import fi.vm.sade.hakurekisteri.web.rest.support.{
  IncidentReportSwaggerModel,
  ModelResponseMessage,
  OldSwaggerSyntax
}
import fi.vm.sade.hakurekisteri.oppija.Oppija
import org.scalatra.swagger.DataType.{ContainerDataType, ValueDataType}

trait OppijaSwaggerApi
    extends SwaggerSupport
    with OppijaSwaggerModel
    with ArvosanaSwaggerModel
    with SuoritusSwaggerModel
    with OpiskelijaSwaggerModel
    with OpiskeluoikeusSwaggerModel
    with IncidentReportSwaggerModel { this: OppijaResource =>

  registerModel(arvioModel)
  registerModel(lahdeArvotModel)
  registerModel(arvosanaModel)
  registerModel(virallinenSuoritusModel)
  registerModel(vapaamuotoinenSuoritusModel)
  registerModel(opiskelijaModel)
  registerModel(opiskeluoikeusModel)
  registerModel(todistusModel)
  registerModel(oppijaModel)

  val query = apiOperation[Seq[Oppija]]("haeOppijat")
    .summary("näyttää oppijoiden tiedot")
    .description(
      "Näyttää listauksen oppijoiden tiedoista parametrien mukaisesti. Hakuoid on pakollinen mikäli halutaan saada ensikertalaisuustiedot."
    )
    .parameter(
      queryParam[Option[String]]("haku")
        .description("haun oid")
        .optional
    )
    .parameter(
      queryParam[Option[String]]("organisaatio")
        .description("koulutuksen tarjoajan tai sen yläorganisaation oid")
        .optional
    )
    .parameter(
      queryParam[Option[String]]("hakukohde")
        .description("hakukohteen oid")
        .optional
    )
    .parameter(
      queryParam[Option[Boolean]]("ensikertalaisuudet")
        .description("palautetaanko ensikertalaisuustiedot")
        .optional
        .defaultValue(Some(true))
    )
    .responseMessage(ModelResponseMessage(400, "[invalid parameter description]"))
    .responseMessage(ModelResponseMessage(404, "haku not found"))
    .responseMessage(ModelResponseMessage(500, "virta error"))
    .responseMessage(ModelResponseMessage(500, "back-end service timed out"))
    .responseMessage(ModelResponseMessage(500, "error in service"))
    .responseMessage(
      ModelResponseMessage(503, "hakemukset not yet loaded: utilise Retry-After response header")
    )
    .tags("oppijat")

  val read = apiOperation[Oppija]("haeOppija")
    .summary("näyttää yhden oppijan tiedot")
    .description(
      "Näyttää yhden oppijan tiedot oppijanumeron perusteella. Jos haun oidia ei anneta parametrinä, ensikertalaisuus-tietoa ei palauteta."
    )
    .parameter(
      pathParam[String]("oid")
        .description("oppijanumero")
        .required
    )
    .parameter(
      queryParam[String]("haku")
        .description("haun oid")
        .optional
    )
    .responseMessage(ModelResponseMessage(400, "[invalid parameter description]"))
    .responseMessage(ModelResponseMessage(404, "haku not found"))
    .responseMessage(ModelResponseMessage(500, "virta error"))
    .responseMessage(ModelResponseMessage(500, "back-end service timed out"))
    .responseMessage(ModelResponseMessage(500, "error in service"))
    .responseMessage(
      ModelResponseMessage(503, "hakemukset not yet loaded: utilise Retry-After response header")
    )
    .tags("oppijat")

  val post = apiOperation[Seq[Oppija]]("haeOppijatPost")
    .summary("näyttää oppijoiden tiedot oppijanumerolistan perusteella")
    .description(
      "Näyttää listauksen oppijoiden tiedoista lähetetyn oppijanumerolistan perusteella."
    )
    .parameter(
      bodyParam[String]("oppijanumerot")
        .description(
          s"""lista oppijanumeroista (max ${OppijatPostSize.maxOppijatPostSize} kpl), esim ["1.2.246.562.24.00000000001", "1.2.246.562.24.00000000002"]"""
        )
        .required
    )
    .parameter(
      queryParam[String]("haku")
        .description("haun oid")
        .required
    )
    .parameter(
      queryParam[Option[Boolean]]("ensikertalaisuudet")
        .description("palautetaanko ensikertalaisuustiedot")
        .optional
        .defaultValue(Some(true))
    )
    .responseMessage(ModelResponseMessage(400, "[invalid parameter description]"))
    .responseMessage(ModelResponseMessage(404, "haku not found"))
    .responseMessage(ModelResponseMessage(500, "back-end service timed out"))
    .responseMessage(ModelResponseMessage(500, "error in service"))
    .tags("oppijat")

}

trait OppijaSwaggerModel extends OldSwaggerSyntax {

  val oppijaFields = Seq(
    ModelField("oppijanumero", null, DataType.String),
    ModelField(
      "opiskelu",
      null,
      ContainerDataType("List", Some(ValueDataType("Opiskelija", None, Some("Opiskelija"))))
    ),
    ModelField(
      "suoritukset",
      null,
      ContainerDataType("List", Some(ValueDataType("Todistus", None, Some("Todistus"))))
    ),
    ModelField(
      "opiskeluoikeudet",
      null,
      ContainerDataType("List", Some(ValueDataType("Opiskeluoikeus", None, Some("Opiskeluoikeus"))))
    ),
    ModelField("ensikertalainen", null, DataType.Boolean, required = false)
  )

  val todistusFields = Seq(
    ModelField("suoritus", null, ValueDataType("Suoritus", None, Some("Suoritus"))),
    ModelField(
      "arvosanat",
      null,
      ContainerDataType("List", Some(ValueDataType("Arvosana", None, Some("Arvosana"))))
    )
  )

  def todistusModel = Model("Todistus", "Todistus", todistusFields.map(t => (t.name, t)).toMap)
  def oppijaModel = Model("Oppija", "Oppija", oppijaFields.map(t => (t.name, t)).toMap)

}
