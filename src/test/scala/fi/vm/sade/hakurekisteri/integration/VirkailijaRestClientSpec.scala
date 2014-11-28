package fi.vm.sade.hakurekisteri.integration

import akka.actor.{Props, ActorSystem}
import org.scalatest.{Matchers, FlatSpec}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future, ExecutionContext}
import com.ning.http.client._
import org.scalatest.mock.MockitoSugar
import org.mockito.Mockito._
import scala.Some
import org.mockito.Mockito

class VirkailijaRestClientSpec extends FlatSpec with Matchers with MockitoSugar with DispatchSupport {
  implicit val system = ActorSystem("test-virkailija")
  implicit val ec: ExecutionContext = system.dispatcher

  def createEndpointMock = {
    val result = mock[Endpoint]

    when(result.request(forUrl("http://localhost/cas/v1/tickets"))).thenReturn((201, List("Location" -> "http://localhost/cas/v1/tickets/TGT-123"), ""))
    when(result.request(forUrl("http://localhost/cas/v1/tickets/TGT-123"))).thenReturn((200,List(), "ST-123"))

    when(result.request(forUrl("http://localhost/test/rest/blaa"))).thenReturn((200, List(), "{\"id\":\"abc\"}"))
    when(result.request(forUrl("http://localhost/test/rest/throwMe"))).thenReturn((404, List(), "Not Found"))
    when(result.request(forUrl("http://localhost/test/rest/invalidContent"))).thenReturn((200, List(), "invalid content"))


    when(result.request(forUrl("http://localhost/cas2/v1/tickets"))).thenReturn((201, List("Location" -> "http://localhost/cas2/v1/tickets/TGT-124"), ""))
    when(result.request(forUrl("http://localhost/cas2/v1/tickets/TGT-124"))).thenReturn((200,List(), "ST-124"))

    when(result.request(forUrl("http://localhost/blast/rest/foo").withHeader("Cookie" -> "JSESSIONID=abcd"))).thenReturn((200, List(), "{\"id\":\"abc\"}"))
    when(result.request(forUrl("http://localhost/blast/j_spring_cas_security_check?ticket=ST-124"))).thenReturn((200, List("Set-Cookie" -> s"${JSessionIdCookieParser.name}=abcd"), ""))

    result
  }

  val endPoint = createEndpointMock


  val asyncProvider = new CapturingProvider(endPoint)

  val client = new VirkailijaRestClient(ServiceConfig(serviceUrl = "http://localhost/test"), aClient = Some(new AsyncHttpClient(asyncProvider)))

  behavior of "VirkailijaRestClient"

  it should "make request to specified url" in {
    client.client("/rest/blaa")



    Mockito.verify(endPoint, Mockito.timeout(200).atLeastOnce()).request(forUrl("http://localhost/test/rest/blaa"))

  }

  import VirkailijaRestImplicits._
  it should "serialize response into a case class" in {
    val response =  client.client("/rest/blaa".accept(200).as[TestResponse])
    val testResponse = Await.result(response, 10.seconds)
    testResponse.id should be("abc")
  }

  it should "throw PreconditionFailedException if undesired response code was returned from the remote service" in {
    val response = client.readObject[TestResponse]("/rest/throwMe", 200)
    intercept[PreconditionFailedException] {
      Await.result(response, 10.seconds)
    }
  }

  it should "throw Exception if invalid content was returned from the remote service" in {
    intercept[Exception] {
      val response = client.readObject[TestResponse]("/rest/invalidContent", 200)
      Await.result(response, 10.seconds)
    }
  }

  it should "send JSESSIONID cookie in requests" in {
    val sessionEndPoint = createEndpointMock

    val sessionClient = new VirkailijaRestClient(ServiceConfig(casUrl = Some("http://localhost/cas2"),
      serviceUrl = "http://localhost/blast",
      user = Some("user"),
      password = Some("pw")),
      Some(new AsyncHttpClient(new CapturingProvider(sessionEndPoint)))
    )

    val requestChain: Future[TestResponse] = sessionClient.readObject[TestResponse]("/rest/foo", 200).flatMap {
      case _ => sessionClient.readObject[TestResponse]("/rest/foo", 200).flatMap {
        case _ => sessionClient.readObject[TestResponse]("/rest/foo", 200)
      }
    }

    Await.ready(requestChain, 30.seconds)
    val ehti = requestChain.isCompleted
    if (ehti)
      verify(sessionEndPoint, times(3)).request(forUrl("http://localhost/blast/rest/foo").withHeader("Cookie" -> "JSESSIONID=abcd"))
    else
      fail("timed out")
  }

  case class TestResponse(id: String)




}