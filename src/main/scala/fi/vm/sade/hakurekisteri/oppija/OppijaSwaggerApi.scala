package fi.vm.sade.hakurekisteri.oppija

import fi.vm.sade.hakurekisteri.arvosana.ArvosanaSwaggerModel
import fi.vm.sade.hakurekisteri.opiskelija.OpiskelijaSwaggerModel
import fi.vm.sade.hakurekisteri.opiskeluoikeus.OpiskeluoikeusSwaggerModel
import fi.vm.sade.hakurekisteri.rest.support.{IncidentReportSwaggerModel, ModelResponseMessage}
import fi.vm.sade.hakurekisteri.suoritus.SuoritusSwaggerModel
import org.scalatra.swagger.{DataType, SwaggerSupport}

trait OppijaSwaggerApi extends SwaggerSupport with ArvosanaSwaggerModel with SuoritusSwaggerModel with OpiskelijaSwaggerModel with OpiskeluoikeusSwaggerModel with IncidentReportSwaggerModel {

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

  registerModel(arvioModel)
  registerModel(arvosanaModel)
  //registerModel(suoritusModel)
  registerModel(virallinenSuoritusModel)
  registerModel(vapaamuotoinenSuoritusModel)
  registerModel(opiskelijaModel)
  registerModel(opiskeluoikeusModel)
  registerModel(Model("Todistus", "Todistus", todistusFields.map(t => (t.name, t)).toMap))
  registerModel(Model("Oppija", "Oppija", oppijaFields.map(t => (t.name, t)).toMap))

  val query = apiOperation[Seq[Oppija]]("haeOppijat")
    .summary("näyttää oppijoiden tiedot")
    .notes("Näyttää listauksen oppijoiden tiedoista parametrien mukaisesti.")
    .parameter(queryParam[Option[String]]("haku").description("haun oid").optional)
    .parameter(queryParam[Option[String]]("organisaatio").description("koulutuksen tarjoajan tai sen yläorganisaation oid").optional)
    .parameter(queryParam[Option[String]]("hakukohde").description("hakukohteen oid").optional)
    .responseMessage(ModelResponseMessage(400, "[invalid parameter description]"))
    .responseMessage(ModelResponseMessage(500, "virta error"))
    .responseMessage(ModelResponseMessage(500, "back-end service timed out"))
    .responseMessage(ModelResponseMessage(500, "error in service"))

  val read = apiOperation[Oppija]("haeOppija")
    .summary("näyttää yhden oppijan tiedot")
    .notes("Näyttää yhden oppijan tiedot oppijanumeron perusteella.")
    .parameter(pathParam[String]("oid").description("oppijanumero").required)
    .responseMessage(ModelResponseMessage(400, "[invalid parameter description]"))
    .responseMessage(ModelResponseMessage(500, "virta error"))
    .responseMessage(ModelResponseMessage(500, "back-end service timed out"))
    .responseMessage(ModelResponseMessage(500, "error in service"))

}
