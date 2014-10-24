package fi.vm.sade.hakurekisteri.ensikertalainen

import org.scalatra.swagger.AllowableValues.AnyValue
import org.scalatra.swagger.{DataType, SwaggerSupport}
import org.scalatra.swagger.SwaggerSupportSyntax.OperationBuilder
import fi.vm.sade.hakurekisteri.rest.support.OldSwaggerSyntax

trait EnsikertalainenSwaggerApi extends SwaggerSupport with OldSwaggerSyntax {
  override protected val applicationName = Some("ensikertalainen")

  val fields = Seq(ModelField("ensikertalainen", null, DataType.Boolean, None, AnyValue, required = true))

  registerModel(Model("Ensikertalainen", "Ensikertalainen", fields.map{ t => (t.name, t) }.toMap))

  val query: OperationBuilder = apiOperation[Ensikertalainen]("haeEnsikertalaisuus")
    .summary("tarkistaa onko hakija ensikertalainen")
    .notes("Tarkistaa onko hakija ensikertalainen.")
    .parameter(queryParam[Option[String]]("henkilo").description("hakijan oppijanumero").optional)
}
