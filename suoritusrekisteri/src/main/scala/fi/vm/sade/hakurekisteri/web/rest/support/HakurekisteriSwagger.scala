package fi.vm.sade.hakurekisteri.web.rest.support

import org.json4s.Formats
import org.scalatra._
import org.scalatra.{ScalatraBase, ScalatraServlet}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import org.scalatra.swagger.{
  ApiInfo,
  ContactInfo,
  DataType,
  JacksonSwaggerBase,
  LicenseInfo,
  ResponseMessage,
  Swagger
}

import jakarta.servlet.ServletConfig

class ResourcesApp(forceHttps: Boolean)(implicit val swagger: Swagger)
    extends ScalatraServlet
    with HakurekisteriJsonSupport
    with JacksonSwaggerBase {
  val hakurekisteriFormats = super[HakurekisteriJsonSupport].jsonFormats
  override implicit val jsonFormats: Formats =
    super[JacksonSwaggerBase].jsonFormats ++ hakurekisteriFormats.customSerializers

  override def init(config: ServletConfig): Unit = {
    super.init(config)
    servletContext.setInitParameter(ScalatraBase.ForceHttpsKey, forceHttps.toString)
  }
}

class HakurekisteriSwagger
    extends Swagger(
      Swagger.SpecVersion,
      "1",
      ApiInfo(
        title = "Haku- ja valintarekisteri",
        description = "rekisteri opiskelijavalintojen suorittamiseen tarvittaviin tietoihin",
        termsOfServiceUrl = "https://opintopolku.fi/wp/fi/opintopolku/tietoa-palvelusta/",
        contact = ContactInfo("verkkotoimitus", "(placeholder) url", "verkkotoimitus_opintopolku@oph.fi"),
        license = LicenseInfo("license", "http://www.osor.eu/eupl/")
      )
    )

class ModelResponseMessage(
  override val code: Int,
  override val message: String,
  override val responseModel: Option[String]
) extends ResponseMessage(code, message, responseModel)

object ModelResponseMessage {
  def apply(
    code: Int,
    message: String,
    responseModel: Option[String] = Some("IncidentReport")
  ): ModelResponseMessage = {
    new ModelResponseMessage(code, message, responseModel)
  }
}

trait IncidentReportSwaggerModel extends OldSwaggerSyntax {

  def incidentReportModel = Model(
    "IncidentReport",
    "IncidentReport",
    Seq(
      ModelField("incidentId", "virheen tunniste, UUID", DataType.String),
      ModelField("message", "viesti", DataType.String),
      ModelField("timestamp", "ajanhetki", DataType.Date)
    ).map { t => (t.name, t) }.toMap
  )

}
