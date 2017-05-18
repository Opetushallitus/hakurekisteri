package fi.vm.sade.hakurekisteri.rest

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{TestProbe, TestActorRef}
import fi.vm.sade.hakurekisteri.integration.hakemus._
import fi.vm.sade.hakurekisteri.integration.ytl._
import fi.vm.sade.hakurekisteri.integration.{DispatchSupport, Endpoint, ExecutorUtil}
import fi.vm.sade.hakurekisteri.web.integration.ytl.YtlResource
import fi.vm.sade.hakurekisteri.web.rest.support.{HakurekisteriSwagger, Security}
import fi.vm.sade.scalaproperties.OphProperties
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.mock.MockitoSugar
import org.scalatra.swagger.Swagger
import org.scalatra.test.scalatest.ScalatraFunSuite
import org.scalamock.scalatest.MockFactory
import org.scalatest.FlatSpec

import scala.concurrent.Future

@RunWith(classOf[JUnitRunner])
class YtlResourceSpec extends ScalatraFunSuite with DispatchSupport with YtlMockFixture with MockFactory {
  implicit val system = ActorSystem()
  implicit val clientEc = ExecutorUtil.createExecutor(1, "ytl-resource-test-pool")
  implicit val swagger: Swagger = new HakurekisteriSwagger
  implicit val adminSecurity: Security = new SuoritusResourceAdminTestSecurity
  val hakemusService = stub[IHakemusService]
  val fileSystem = new YtlFileSystem(ytlProperties)
  val ytlHttpFetch = new YtlHttpFetch(ytlProperties,fileSystem)
  val ytlIntegration = new YtlIntegration(ytlProperties, ytlHttpFetch, fileSystem, hakemusService, new TestProbe(system).ref)
  val someKkHaku = "kkhaku"
  ytlIntegration.setAktiivisetKKHaut(Set(someKkHaku))

  val answers = HakemusAnswers(henkilotiedot= Some(HakemusHenkilotiedot(Henkilotunnus=Some("050996-9574"))))
  val hakemusWithPersonOidEnding9574 = Future.successful(Seq(FullHakemus("",Some("050996-9574"),someKkHaku,Some(answers),Some("ACTIVE"),Seq(),Seq())))

  addServlet(new YtlResource(null, ytlIntegration), "/*")

  val endPoint = mock[Endpoint]

  test("should launch YTL fetch") {
    post("/http_request") {
      status should be (202)
    }
    (hakemusService.hakemuksetForPerson _) when(*) returns(hakemusWithPersonOidEnding9574)
    get("/http_request/050996-9574") {
      status should be (202)
    }
  }
}
