package fi.vm.sade.hakurekisteri.web.restrictions

import fi.vm.sade.hakurekisteri.web.rest.support.{ModelResponseMessage, OldSwaggerSyntax, IncidentReportSwaggerModel}
import org.scalatra.swagger.SwaggerSupport
import org.scalatra.swagger.SwaggerSupportSyntax.OperationBuilder

trait RestrictionsSwaggerApi  extends SwaggerSupport with IncidentReportSwaggerModel with OldSwaggerSyntax {
  override protected val applicationDescription = "rest/v1/restrictions"

  val queryRestriction: OperationBuilder = apiOperation[Boolean]("isRestrictionActiveForKey")
    .summary("checks if any restriction is active for the key")
    .parameter(pathParam[String]("restriction").description("restriction").required)
    .responseMessage(ModelResponseMessage(400, "parameter restriction missing"))
    .responseMessage(ModelResponseMessage(404, "restriction not found"))
    .responseMessage(ModelResponseMessage(500, "back-end service timed out"))
    .responseMessage(ModelResponseMessage(500, "backend service failed"))
}
