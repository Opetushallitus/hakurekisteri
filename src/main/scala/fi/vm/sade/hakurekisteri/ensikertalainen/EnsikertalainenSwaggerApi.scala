package fi.vm.sade.hakurekisteri.ensikertalainen

import java.util.UUID

import org.scalatra.swagger.{DataType, SwaggerSupport}
import org.scalatra.swagger.SwaggerSupportSyntax.OperationBuilder
import fi.vm.sade.hakurekisteri.rest.support.{IncidentReport, IncidentResponseMessage, OldSwaggerSyntax}

trait EnsikertalainenSwaggerApi extends SwaggerSupport with OldSwaggerSyntax {
  override protected val applicationName = Some("rest/v1/ensikertalainen")

  val fields = Seq(ModelField("ensikertalainen", null, DataType.Boolean))

  registerModel(Model("Ensikertalainen", "Ensikertalainen", fields.map{ t => (t.name, t) }.toMap))

  val query: OperationBuilder = apiOperation[Ensikertalainen]("haeEnsikertalaisuus")
    .summary("tarkistaa onko hakija ensikertalainen")
    .notes("Tarkistaa onko hakija ensikertalainen.")
    .parameter(queryParam[String]("henkilo").description("hakijan oppijanumero").required)
    .responseMessage(IncidentResponseMessage(400, IncidentReport(UUID.randomUUID(), "parameter henkilo missing")))
    .responseMessage(IncidentResponseMessage(400, IncidentReport(UUID.randomUUID(), "henkilo does not have hetu; add hetu and try again")))

}
