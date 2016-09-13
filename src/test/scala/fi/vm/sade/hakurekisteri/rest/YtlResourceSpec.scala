package fi.vm.sade.hakurekisteri.rest

import akka.actor.ActorSystem
import fi.vm.sade.hakurekisteri.integration.ytl.{YtlFileSystem, YtlHttpFetch, YtlIntegration}
import fi.vm.sade.hakurekisteri.integration.{DispatchSupport, Endpoint, ExecutorUtil, OphUrlProperties}
import fi.vm.sade.hakurekisteri.web.integration.ytl.YtlResource
import fi.vm.sade.hakurekisteri.web.rest.support.{HakurekisteriSwagger, Security}
import org.scalatest.mock.MockitoSugar
import org.scalatra.swagger.Swagger
import org.scalatra.test.scalatest.ScalatraFunSuite

class YtlResourceSpec extends ScalatraFunSuite with DispatchSupport with MockitoSugar {
  implicit val system = ActorSystem()
  implicit val clientEc = ExecutorUtil.createExecutor(1, "ytl-resource-test-pool")
  implicit val swagger: Swagger = new HakurekisteriSwagger
  implicit val adminSecurity: Security = new SuoritusResourceAdminTestSecurity

  val fetch = new YtlHttpFetch(OphUrlProperties, new YtlFileSystem(OphUrlProperties))
  addServlet(new YtlResource(null, new YtlIntegration(fetch, null, null)), "/*")

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
