package fi.vm.sade.hakurekisteri.rest

import akka.actor.Actor.Receive
import akka.actor.{Actor, ActorSystem, Props}
import akka.testkit.TestActorRef
import com.ning.http.client.AsyncHttpClient
import fi.vm.sade.hakurekisteri.integration.hakemus.HasPermission
import fi.vm.sade.hakurekisteri.integration.henkilo.{Henkilo, HetuQuery}
import fi.vm.sade.hakurekisteri.integration.{CapturingProvider, DispatchSupport, Endpoint, ExecutorUtil}
import fi.vm.sade.hakurekisteri.integration.virta.{VirtaClient, VirtaResourceActor, VirtaResults}
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

  val permissionChecker = TestActorRef(new Actor {
    override def receive: Receive = {
      case d: HasPermission => sender ! true
    }
  })

  val henkiloActor = TestActorRef(new Actor {
    override def receive: Receive = {
      case HetuQuery(hetu) => sender ! Henkilo(
        "1.2.4",
        Some("111111-1975"),
        "OPPIJA",
        None,
        None,
        None,
        None,
        None
      )
    }
  })


  addServlet(new VirtaSuoritusResource(virtaSuoritusActor, permissionChecker, henkiloActor), "/*")


  test("should return required fields from Virta response") {
    get("/1.2.4") {
      status should be (200)
    }
  }
}
