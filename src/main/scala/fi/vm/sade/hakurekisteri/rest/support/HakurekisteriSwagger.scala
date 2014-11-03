package fi.vm.sade.hakurekisteri.rest.support

import org.json4s.Formats
import org.scalatra.swagger._

import org.scalatra.ScalatraServlet


class ResourcesApp(implicit val swagger: Swagger) extends ScalatraServlet with HakurekisteriJsonSupport with JacksonSwaggerBase {
  val hakurekisteriFormats = super[HakurekisteriJsonSupport].jsonFormats
  override implicit val jsonFormats: Formats = super[JacksonSwaggerBase].jsonFormats ++ hakurekisteriFormats.customSerializers
}

class HakurekisteriSwagger extends Swagger(Swagger.SpecVersion, "1", ApiInfo(
  title = "Haku- ja valintarekisteri",
  description = "rekisteri opiskelijavalintojen suorittamiseen tarvittaviin tietoihin",
  termsOfServiceUrl = "https://opintopolku.fi/wp/fi/opintopolku/tietoa-palvelusta/",
  contact = "verkkotoimitus_opintopolku@oph.fi",
  license = "EUPL 1.1 or latest approved by the European Commission" ,
  licenseUrl = "http://www.osor.eu/eupl/")
)

case class ModelResponseMessage(code: Int, message: String, responseModel: String = "IncidentReport") extends ResponseMessage[String]

trait IncidentReportSwaggerModel extends OldSwaggerSyntax {

  def incidentReportModel = Model("IncidentReport", "IncidentReport", Seq(
    ModelField("incidentId", "virheen tunniste, UUID", DataType.String),
    ModelField("message", "viesti", DataType.String),
    ModelField("timestamp", "ajanhetki", DataType.Date)
  ).map{ t => (t.name, t)}.toMap)

}