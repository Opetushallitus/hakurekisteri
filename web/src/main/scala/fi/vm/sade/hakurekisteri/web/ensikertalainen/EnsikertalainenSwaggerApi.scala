package fi.vm.sade.hakurekisteri.web.ensikertalainen

import org.scalatra.swagger.AllowableValues.AllowableValuesList
import org.scalatra.swagger.{DataType, SwaggerSupport}
import org.scalatra.swagger.SwaggerSupportSyntax.OperationBuilder
import fi.vm.sade.hakurekisteri.web.rest.support.{ModelResponseMessage, IncidentReportSwaggerModel, OldSwaggerSyntax}
import fi.vm.sade.hakurekisteri.ensikertalainen._

trait EnsikertalainenSwaggerApi extends SwaggerSupport with IncidentReportSwaggerModel with OldSwaggerSyntax {
  override protected val applicationName = Some("rest/v1/ensikertalainen")

  val perusteFields = Seq(
    ModelField("peruste", null, DataType.String, allowableValues = AllowableValuesList(List("KkVastaanotto", "SuoritettuKkTutkinto"))),
    ModelField("paivamaara", null, DataType.DateTime)
  )

  registerModel(Model("MenettamisenPeruste", "MenettamisenPeruste", perusteFields.map{ t => (t.name, t) }.toMap))

  val fields = Seq(
    ModelField("ensikertalainen", null, DataType.Boolean),
    ModelField("menettamisenPeruste", null, DataType("MenettamisenPeruste"), required = false)
  )

  registerModel(Model("Ensikertalainen", "Ensikertalainen", fields.map{ t => (t.name, t) }.toMap))

  registerModel(incidentReportModel)

  val query: OperationBuilder = apiOperation[Ensikertalainen]("haeEnsikertalaisuus")
    .summary("tarkistaa onko hakija ensikertalainen")
    .notes("Tarkistaa onko hakija ensikertalainen.")
    .parameter(queryParam[String]("henkilo").description("hakijan oppijanumero").required)
    .parameter(queryParam[String]("haku").description("haun oid").required)
    .responseMessage(ModelResponseMessage(400, "parameter henkilo or haku missing"))
    .responseMessage(ModelResponseMessage(500, "back-end service timed out"))
    .responseMessage(ModelResponseMessage(500, "backend service failed"))

  val hakuQuery: OperationBuilder = apiOperation[Seq[Ensikertalainen]]("haeEnsikertalaisuudetHaulle")
    .summary("tarkistaa ovatko haun hakijat ensikertalaisia")
    .notes("Tarkistaa ovatko haun hakijat ensikertalaisia.")
    .parameter(pathParam[String]("haku").description("haun oid").required)
    .responseMessage(ModelResponseMessage(400, "parameter haku missing"))
    .responseMessage(ModelResponseMessage(500, "back-end service timed out"))
    .responseMessage(ModelResponseMessage(500, "backend service failed"))

  val postQuery: OperationBuilder = apiOperation[Seq[Ensikertalainen]]("haeEnsikertalaisuudet")
    .summary("tarkistaa ovatko hakijat ensikertalaisia")
    .notes("Tarkistaa ovatko hakijat ensikertalaisia.")
    .parameter(bodyParam[Seq[String]]("henkilot").description("hakijoidet oppijanumerot").required)
    .parameter(queryParam[String]("haku").description("haun oid").required)
    .responseMessage(ModelResponseMessage(400, "request body does not contain person oids"))
    .responseMessage(ModelResponseMessage(500, "back-end service timed out"))
    .responseMessage(ModelResponseMessage(500, "backend service failed"))

}
