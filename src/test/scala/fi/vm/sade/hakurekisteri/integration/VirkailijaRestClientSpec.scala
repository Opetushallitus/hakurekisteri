package fi.vm.sade.hakurekisteri.integration

import akka.actor.ActorSystem
import com.ning.http.client._
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterEach, FlatSpec, Matchers}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}


class VirkailijaRestClientSpec extends FlatSpec with Matchers with MockitoSugar with DispatchSupport with BeforeAndAfterEach with LocalhostProperties {
  implicit val system = ActorSystem("test-virkailija")
  implicit val ec: ExecutionContext = system.dispatcher
  val endPoint = mock[Endpoint]

  override def beforeEach() {
    OphUrlProperties.overrides.setProperty("test.rest","/test/rest")
    super.beforeEach()
    reset(endPoint)
  }

  val client = new VirkailijaRestClient(ServiceConfig(serviceUrl = "http://localhost/test"),aClient = Some(new AsyncHttpClient(new CapturingProvider(endPoint))))

  behavior of "VirkailijaRestClient"

  it should "serialize response into a case class" in {
    when(endPoint.request(forUrl("http://localhost/test/rest"))).thenReturn((200, List(), "{\"id\":\"abc\"}"))

    val response =  client.Client.request("http://localhost/test/rest")(JsonExtractor.handler[TestResponse](200))
    val testResponse = Await.result(response, 10.seconds)
    testResponse.id should be("abc")
  }

  it should "throw PreconditionFailedException if undesired response code was returned from the remote service" in {
    when(endPoint.request(forUrl("http://localhost/test/rest"))).thenReturn((404, List(), "Not Found"))

    val response = client.readObject[TestResponse]("test.rest")(200)
    val thrown = intercept[PreconditionFailedException] {
      Await.result(response, 10.seconds)
    }
    assert(thrown.getMessage === "precondition failed for url: http://localhost/test/rest, status code: 404, body: Not Found")
  }

  it should "throw Exception if invalid content was returned from the remote service" in {
    when(endPoint.request(forUrl("http://localhost/test/rest"))).thenReturn((200, List(), "invalid content"))
    val thrown = intercept[Exception] {
      val response = client.readObject[TestResponse]("test.rest")(200)
      Await.result(response, 10.seconds)
    }
    thrown.getMessage() should include("Unrecognized token 'invalid': was expecting ('true', 'false' or 'null')")
  }

  it should "send JSESSIONID cookie in requests" in {
    when(endPoint.request(forUrl("http://localhost/blast/j_spring_cas_security_check?ticket=ST-124"))).thenReturn((200, List("Set-Cookie" -> s"${JSessionIdCookieParser.name}=abcd"), ""))
    when(endPoint.request(forUrl("http://localhost/cas2/v1/tickets"))).thenReturn((201, List("Location" -> "http://localhost/cas2/v1/tickets/TGT-124"), ""))
    when(endPoint.request(forUrl("http://localhost/cas2/v1/tickets/TGT-124"))).thenReturn((200,List(), "ST-124"))
    when(endPoint.request(forUrl("http://localhost/test/rest").withHeader("Cookie" -> "JSESSIONID=abcd"))).thenReturn((200, List(), "{\"id\":\"abc\"}"))

    val sessionClient = new VirkailijaRestClient(ServiceConfig(casUrl = Some("http://localhost/cas2"),
      serviceUrl = "http://localhost/blast",
      user = Some("user"),
      password = Some("pw")),
      Some(new AsyncHttpClient(new CapturingProvider(endPoint)))
    )

    val requestChain: Future[TestResponse] = sessionClient.readObject[TestResponse]("test.rest")(200).flatMap {
      case _ => sessionClient.readObject[TestResponse]("test.rest")(200).flatMap {
        case _ => sessionClient.readObject[TestResponse]("test.rest")(200)
      }
    }

    Await.ready(requestChain, 30.seconds)
    val ehti = requestChain.isCompleted
    if (ehti)
      verify(endPoint, times(3)).request(forUrl("http://localhost/test/rest").withHeader("Cookie" -> "JSESSIONID=abcd"))
    else
      fail("timed out")
  }

  case class TestResponse(id: String)
}
