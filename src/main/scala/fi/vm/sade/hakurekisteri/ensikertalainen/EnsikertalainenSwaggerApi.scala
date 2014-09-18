package fi.vm.sade.hakurekisteri.ensikertalainen

import org.scalatra.swagger.AllowableValues.AnyValue
import org.scalatra.swagger.{Model, DataType, ModelField, SwaggerSupport}
import org.scalatra.swagger.SwaggerSupportSyntax.OperationBuilder

trait EnsikertalainenSwaggerApi extends SwaggerSupport {
  override protected val applicationName = Some("ensikertalainen")

  val fields = Seq(ModelField("ensikertalainen", null, DataType.Boolean, None, AnyValue, required = true))

  registerModel(Model("Ensikertalainen", "Ensikertalainen", fields.map{ t => (t.name, t) }.toMap))

  val query: OperationBuilder = apiOperation[Ensikertalainen]("haeEnsikertalaisuus")
    .summary("tarkistaa onko hakija ensikertalainen")
    .notes("Tarkistaa onko hakija ensikertalainen.")
    .parameter(queryParam[Option[String]]("henkilo").description("hakijan oppijanumero").optional)
}
