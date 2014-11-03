package fi.vm.sade.hakurekisteri.ensikertalainen

import org.scalatra.swagger.{DataType, SwaggerSupport}
import org.scalatra.swagger.SwaggerSupportSyntax.OperationBuilder
import fi.vm.sade.hakurekisteri.rest.support.{ModelResponseMessage, IncidentReportSwaggerModel, OldSwaggerSyntax}

trait EnsikertalainenSwaggerApi extends SwaggerSupport with IncidentReportSwaggerModel with OldSwaggerSyntax {
  override protected val applicationName = Some("rest/v1/ensikertalainen")

  val fields = Seq(ModelField("ensikertalainen", null, DataType.Boolean))

  registerModel(Model("Ensikertalainen", "Ensikertalainen", fields.map{ t => (t.name, t) }.toMap))

  registerModel(incidentReportModel)

  val query: OperationBuilder = apiOperation[Ensikertalainen]("haeEnsikertalaisuus")
    .summary("tarkistaa onko hakija ensikertalainen")
    .notes("Tarkistaa onko hakija ensikertalainen.")
    .parameter(queryParam[String]("henkilo").description("hakijan oppijanumero").required)
    .responseMessage(ModelResponseMessage(400, "parameter henkilo missing"))
    .responseMessage(ModelResponseMessage(400, "henkilo does not have hetu; add hetu and try again"))
    .responseMessage(ModelResponseMessage(500, "back-end service timed out"))
    .responseMessage(ModelResponseMessage(500, "backend service failed"))

}
