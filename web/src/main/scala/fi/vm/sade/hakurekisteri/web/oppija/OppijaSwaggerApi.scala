package fi.vm.sade.hakurekisteri.web.oppija

import org.scalatra.swagger.{DataType, SwaggerSupport}
import fi.vm.sade.hakurekisteri.web.arvosana.ArvosanaSwaggerModel
import fi.vm.sade.hakurekisteri.web.opiskelija.OpiskelijaSwaggerModel
import fi.vm.sade.hakurekisteri.web.opiskeluoikeus.OpiskeluoikeusSwaggerModel
import fi.vm.sade.hakurekisteri.web.suoritus.SuoritusSwaggerModel
import fi.vm.sade.hakurekisteri.web.rest.support.{OldSwaggerSyntax, ModelResponseMessage, IncidentReportSwaggerModel}
import fi.vm.sade.hakurekisteri.oppija.Oppija

trait OppijaSwaggerApi
    extends SwaggerSupport with OppijaSwaggerModel with ArvosanaSwaggerModel with SuoritusSwaggerModel
    with OpiskelijaSwaggerModel with OpiskeluoikeusSwaggerModel with IncidentReportSwaggerModel { this: OppijaResource =>

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
    .notes("Näyttää listauksen oppijoiden tiedoista parametrien mukaisesti.")
    .parameter(queryParam[String]("haku")
      .description("haun oid").required)
    .parameter(queryParam[Option[String]]("organisaatio")
      .description("koulutuksen tarjoajan tai sen yläorganisaation oid").optional)
    .parameter(queryParam[Option[String]]("hakukohde")
      .description("hakukohteen oid").optional)
    .responseMessage(ModelResponseMessage(400, "[invalid parameter description]"))
    .responseMessage(ModelResponseMessage(404, "haku not found"))
    .responseMessage(ModelResponseMessage(500, "virta error"))
    .responseMessage(ModelResponseMessage(500, "back-end service timed out"))
    .responseMessage(ModelResponseMessage(500, "error in service"))
    .responseMessage(ModelResponseMessage(503, "hakemukset not yet loaded: utilise Retry-After response header"))

  val read = apiOperation[Oppija]("haeOppija")
    .summary("näyttää yhden oppijan tiedot")
    .notes("Näyttää yhden oppijan tiedot oppijanumeron perusteella.")
    .parameter(pathParam[String]("oid")
      .description("oppijanumero").required)
    .parameter(queryParam[String]("haku")
      .description("haun oid").required)
    .responseMessage(ModelResponseMessage(400, "[invalid parameter description]"))
    .responseMessage(ModelResponseMessage(404, "haku not found"))
    .responseMessage(ModelResponseMessage(500, "virta error"))
    .responseMessage(ModelResponseMessage(500, "back-end service timed out"))
    .responseMessage(ModelResponseMessage(500, "error in service"))
    .responseMessage(ModelResponseMessage(503, "hakemukset not yet loaded: utilise Retry-After response header"))

  val post = apiOperation[Seq[Oppija]]("haeOppijatPost")
    .summary("näyttää oppijoiden tiedot oppijanumerolistan perusteella")
    .notes("Näyttää listauksen oppijoiden tiedoista lähetetyn oppijanumerolistan perusteella.")
    .parameter(bodyParam[String]("oppijanumerot")
      .description(s"""lista oppijanumeroista (max $maxOppijatPostSize kpl), esim ["1.2.246.562.24.00000000001", "1.2.246.562.24.00000000002"]""").required)
    .parameter(queryParam[String]("haku")
      .description("haun oid").required)
    .responseMessage(ModelResponseMessage(400, "[invalid parameter description]"))
    .responseMessage(ModelResponseMessage(404, "haku not found"))
    .responseMessage(ModelResponseMessage(500, "back-end service timed out"))
    .responseMessage(ModelResponseMessage(500, "error in service"))

}


trait OppijaSwaggerModel extends OldSwaggerSyntax  {

  val oppijaFields = Seq(
    ModelField("oppijanumero", null, DataType.String),
    ModelField("opiskelu", null, DataType.GenList(DataType("Opiskelija"))),
    ModelField("suoritukset", null, DataType.GenList(DataType("Todistus"))),
    ModelField("opiskeluoikeudet", null, DataType.GenList(DataType("Opiskeluoikeus"))),
    ModelField("ensikertalainen", null, DataType.Boolean, required = false)
  )

  val todistusFields = Seq(
    ModelField("suoritus", null, DataType("Suoritus")),
    ModelField("arvosanat", null, DataType.GenList(DataType("Arvosana")))
  )

  def todistusModel = Model("Todistus", "Todistus", todistusFields.map(t => (t.name, t)).toMap)
  def oppijaModel = Model("Oppija", "Oppija", oppijaFields.map(t => (t.name, t)).toMap)

}