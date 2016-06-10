package fi.vm.sade.hakurekisteri.rest

import akka.actor.{ActorSystem, Props}
import com.ning.http.client.AsyncHttpClient
import fi.vm.sade.hakurekisteri.integration.{ExecutorUtil, DispatchSupport, Endpoint, CapturingProvider}
import fi.vm.sade.hakurekisteri.integration.virta.{VirtaResults, VirtaClient, VirtaResourceActor}
import fi.vm.sade.hakurekisteri.web.rest.support._
import org.mockito.Mockito
import org.scalatest.mock.MockitoSugar
import org.scalatra.swagger.Swagger

import org.scalatra.test.scalatest.ScalatraFunSuite

import fi.vm.sade.hakurekisteri.web.integration.virta.VirtaSuoritusResource

class VirtaSuoritusResourceSpec extends ScalatraFunSuite with DispatchSupport with MockitoSugar {
  implicit val system = ActorSystem()
  implicit val clientEc = ExecutorUtil.createExecutor(1, "virta-resource-test-pool")
  implicit val swagger: Swagger = new HakurekisteriSwagger
  implicit val security: Security = new SuoritusResourceTestSecurity

  import Mockito._

  val endPoint = mock[Endpoint]

  when(endPoint.request(forUrl("http://virtawstesti.csc.fi/luku/OpiskelijanTiedot").withBodyPart("1.2.4"))).thenReturn((200, List(), VirtaResults.emptyResp))
  when(endPoint.request(forUrl("http://virtawstesti.csc.fi/luku/OpiskelijanTiedot").withBodyPart("1.2.5"))).thenReturn((500, List(), "Internal Server Error"))
  when(endPoint.request(forUrl("http://virtawstesti.csc.fi/luku/OpiskelijanTiedot").withBodyPart("1.3.0"))).thenReturn((200, List(), VirtaResults.multipleStudents))
  when(endPoint.request(forUrl("http://virtawstesti.csc.fi/luku/OpiskelijanTiedot").withBodyPart("1.5.0"))).thenReturn((500, List(), VirtaResults.fault))
  when(endPoint.request(forUrl("http://virtawstesti.csc.fi/luku/OpiskelijanTiedot").withBodyPart("1.2.3"))).thenReturn((200, List(), VirtaResults.testResponse))
  when(endPoint.request(forUrl("http://virtawstesti.csc.fi/luku/OpiskelijanTiedot").withBodyPart("1.2.9"))).thenReturn((200, List(), VirtaResults.opiskeluoikeustyypit))
  when(endPoint.request(forUrl("http://virtawstesti.csc.fi/luku/OpiskelijanTiedot").withBodyPart("111111-1975"))).thenReturn((200, List(), VirtaResults.testResponse))
  when(endPoint.request(forUrl("http://virtawstesti.csc.fi/luku/OpiskelijanTiedot").withBodyPart("1.2.106"))).thenReturn((200, List(), VirtaResults.testResponse106))

  val virtaClient = new VirtaClient(aClient = Some(new AsyncHttpClient(new CapturingProvider(endPoint))))
  val virtaSuoritusActor = system.actorOf(Props(new VirtaResourceActor(virtaClient)))



  addServlet(new VirtaSuoritusResource(virtaSuoritusActor), "/*")


  test("should return required fields from Virta response") {
    get("/1.2.4") {
      status should be (200)
    }
  }
}
