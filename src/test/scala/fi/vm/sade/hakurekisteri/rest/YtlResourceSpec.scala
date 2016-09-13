package fi.vm.sade.hakurekisteri.rest

import akka.actor.ActorSystem
import fi.vm.sade.hakurekisteri.integration.hakemus.HakemusServiceMock
import fi.vm.sade.hakurekisteri.integration.ytl.{YtlMockFixture, YtlHttpFetch, YtlFileSystem, YtlIntegration}
import fi.vm.sade.hakurekisteri.integration.{DispatchSupport, Endpoint, ExecutorUtil}
import fi.vm.sade.hakurekisteri.web.integration.ytl.YtlResource
import fi.vm.sade.hakurekisteri.web.rest.support.{HakurekisteriSwagger, Security}
import fi.vm.sade.scalaproperties.OphProperties
import org.scalatest.mock.MockitoSugar
import org.scalatra.swagger.Swagger
import org.scalatra.test.scalatest.ScalatraFunSuite

class YtlResourceSpec extends ScalatraFunSuite with DispatchSupport with MockitoSugar with YtlMockFixture {
  implicit val system = ActorSystem()
  implicit val clientEc = ExecutorUtil.createExecutor(1, "ytl-resource-test-pool")
  implicit val swagger: Swagger = new HakurekisteriSwagger
  implicit val adminSecurity: Security = new SuoritusResourceAdminTestSecurity

  val fileSystem = new YtlFileSystem(ytlProperties)
  val ytlHttpFetch = new YtlHttpFetch(ytlProperties,fileSystem)
  val ytlIntegration = new YtlIntegration(ytlHttpFetch, new HakemusServiceMock, null)

  addServlet(new YtlResource(null, ytlIntegration), "/*")

  val endPoint = mock[Endpoint]

  test("should launch YTL fetch") {
    get("/http_request") {
      status should be (202)
    }
    get("/http_request/050996-9574") {
      status should be (202)
    }
  }
}
