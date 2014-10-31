package fi.vm.sade.hakurekisteri.rest.support

import org.json4s.Formats
import org.scalatra.swagger.SwaggerSerializers.{ApiSerializer, EndpointSerializer, OperationSerializer}
import org.scalatra.swagger.{ResponseMessage, ApiInfo, JacksonSwaggerBase, Swagger}

import org.scalatra.ScalatraServlet


class ResourcesApp(implicit val swagger: Swagger) extends ScalatraServlet with HakurekisteriJsonSupport with JacksonSwaggerBase {
  val hakurekisteriFormats = super[HakurekisteriJsonSupport].jsonFormats
  override implicit val jsonFormats: Formats = super[JacksonSwaggerBase].jsonFormats ++ hakurekisteriFormats.customSerializers
}

class HakurekisteriSwagger extends Swagger(Swagger.SpecVersion, "1", ApiInfo(
  title = "Haku- ja valintarekisteri",
  description = "rekisteri opiskelijavalintojen suorittamiseen tarvittaviin tietoihin",
  termsOfServiceUrl =  "",
  contact = "",
  license = "" ,
  licenseUrl = "")
)
