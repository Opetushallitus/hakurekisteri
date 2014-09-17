package fi.vm.sade.hakurekisteri.integration

import java.net.URL
import java.util.Date

import akka.actor.ActorSystem
import com.stackmob.newman.request._
import com.stackmob.newman.response.{HttpResponseCode, HttpResponse}
import com.stackmob.newman.{RawBody, Headers, HttpClient}
import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers

import scala.concurrent.duration._
import scala.concurrent.{Await, Future, ExecutionContext}

class VirkailijaRestClientSpec extends FlatSpec with ShouldMatchers {
  implicit val system = ActorSystem("test-virkailija")
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val httpClient: MockHttpClient = new MockHttpClient()
  val client = new VirkailijaRestClient(ServiceConfig(serviceUrl = "http://localhost/test"))

  behavior of "VirkailijaRestClient"

  it should "make request to specified url" in {
    client.executeGet("/rest/blaa")
    httpClient.capturedRequestUrl should be("http://localhost/test/rest/blaa")
  }

  it should "serialize response into a case class" in {
    val response: Future[TestResponse] = client.readObject[TestResponse]("/rest/blaa", HttpResponseCode.Ok)
    val testResponse = Await.result(response, 10.seconds)
    testResponse.id should be("abc")
  }

  it should "throw PreconditionFailedException if undesired response code was returned from the remote service" in {
    intercept[PreconditionFailedException] {
      val response = client.readObject[TestResponse]("/rest/throwMe", HttpResponseCode.Ok)
      val testResponse = Await.result(response, 10.seconds)
    }
  }

  it should "throw Exception if invalid content was returned from the remote service" in {
    intercept[Exception] {
      val response = client.readObject[TestResponse]("/rest/invalidContent", HttpResponseCode.Ok)
      val testResponse = Await.result(response, 10.seconds)
    }
  }

  it should "throw IllegalArgumentException if no password was supplied in the configuration" in {
    intercept[IllegalArgumentException] {
      val client = new VirkailijaRestClient(ServiceConfig(serviceUrl = "http://localhost/test", user = Some("user")))
      val response = client.readObject[TestResponse]("/rest/foo", HttpResponseCode.Ok)
      val testResponse = Await.result(response, 10.seconds)
    }
  }

  it should "attach CasSecurityTicket request header into the remote request" in {
    val client = new VirkailijaRestClient(ServiceConfig(serviceAccessUrl = Some("http://localhost/service-access"),
                                                        serviceUrl = "http://localhost/test",
                                                        user = Some("user"),
                                                        password = Some("pw")))
    val response = client.readObject[TestResponse]("/rest/foo", HttpResponseCode.Ok)
    val testResponse = Await.result(response, 10.seconds)
    httpClient.capturedRequestHeaders.get.head should be(("CasSecurityTicket", "ST-123"))
  }

  case class TestResponse(id: String)

  class MockHttpClient extends HttpClient {
    var capturedRequestUrl: String = ""
    var capturedRequestHeaders: Headers = Headers(List())
    override def get(url: URL, headers: Headers): GetRequest = GetRequest(url, headers) {
      capturedRequestHeaders = headers
      capturedRequestUrl = url.toString
      url.toString match {
        case s if s.contains("throwMe") => Future.successful(HttpResponse(HttpResponseCode.InternalServerError, Headers(List()), RawBody(""), new Date()))
        case s if s.contains("invalidContent") => Future.successful(HttpResponse(HttpResponseCode.Ok, Headers(List()), RawBody("invalid content"), new Date()))
        case _ => Future.successful(HttpResponse(HttpResponseCode.Ok, Headers(List()), RawBody("{\"id\":\"abc\"}"), new Date()))
      }
    }
    override def head(url: URL, headers: Headers): HeadRequest = ???
    override def post(url: URL, headers: Headers, body: RawBody): PostRequest = PostRequest(url, headers, body) {
      url.toString match {
        case s if s.endsWith("/accessTicket") => Future.successful(HttpResponse(HttpResponseCode.Ok, Headers(List()), RawBody("ST-123")))
      }
    }
    override def put(url: URL, headers: Headers, body: RawBody): PutRequest = ???
    override def delete(url: URL, headers: Headers): DeleteRequest = ???
  }
}