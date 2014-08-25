package fi.vm.sade.hakurekisteri.ensikertalainen

import fi.vm.sade.hakurekisteri.hakija.XMLHakijat
import org.scalatra.swagger.AllowableValues.AnyValue
import org.scalatra.swagger.{Model, DataType, ModelField, SwaggerSupport}
import org.scalatra.swagger.SwaggerSupportSyntax.OperationBuilder

trait EnsikertalainenSwaggerApi extends SwaggerSupport {
  override protected val applicationName = Some("ensikertalainen")

  val fields = Seq(ModelField("ensikertalainen", null, DataType.Boolean, None, AnyValue, required = true))

  registerModel(Model("Ensikertalainen", "Ensikertalainen", fields.map{ t => (t.name, t) }.toMap))

  val query: OperationBuilder = apiOperation[XMLHakijat]("haeEnsikertalaisuus")
    .summary("näyttää onko hakija ensikertalainen")
    .notes("Näyttää onko hakija ensikertalainen parametrien mukaisesti.")
    .parameter(queryParam[Option[String]]("henkilo").description("henkilön oppijanumero").optional)
}
