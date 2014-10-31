package fi.vm.sade.hakurekisteri.ensikertalainen

import org.scalatra.swagger.{StringResponseMessage, DataType, SwaggerSupport}
import org.scalatra.swagger.SwaggerSupportSyntax.OperationBuilder
import fi.vm.sade.hakurekisteri.rest.support.OldSwaggerSyntax

trait EnsikertalainenSwaggerApi extends SwaggerSupport with OldSwaggerSyntax {
  override protected val applicationName = Some("rest/v1/ensikertalainen")

  val fields = Seq(ModelField("ensikertalainen", null, DataType.Boolean))

  registerModel(Model("Ensikertalainen", "Ensikertalainen", fields.map{ t => (t.name, t) }.toMap))

  val query: OperationBuilder = apiOperation[Ensikertalainen]("haeEnsikertalaisuus")
    .summary("tarkistaa onko hakija ensikertalainen")
    .notes("Tarkistaa onko hakija ensikertalainen.")
    .parameter(queryParam[String]("henkilo").description("hakijan oppijanumero").required)
    .responseMessage(StringResponseMessage(400, "parameter henkilo missing"))
    .responseMessage(StringResponseMessage(400, "henkilo does not have hetu; add hetu and try again"))
    .responseMessage(StringResponseMessage(500, "back-end service timed out"))
    .responseMessage(StringResponseMessage(500, "backend service failed"))

}
