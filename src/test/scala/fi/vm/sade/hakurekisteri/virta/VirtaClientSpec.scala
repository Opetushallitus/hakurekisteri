package fi.vm.sade.hakurekisteri.virta

import java.net.URL
import java.util.Date

import akka.actor.ActorSystem
import com.stackmob.newman.request._
import com.stackmob.newman.response.{HttpResponseCode, HttpResponse}
import com.stackmob.newman.{RawBody, Headers, HttpClient}
import org.scalatest.FlatSpec
import org.scalatest.matchers.ShouldMatchers

import scala.concurrent.Future

class MockHttpClient extends HttpClient {
  override def post(url: URL, headers: Headers, body: RawBody): PostRequest = PostRequest(url, headers, body) {
    Future.successful(HttpResponse(HttpResponseCode.Ok, Headers(List()), RawBody(body), new Date()))
  }
  override def head(url: URL, headers: Headers): HeadRequest = ???
  override def get(url: URL, headers: Headers): GetRequest = ???
  override def put(url: URL, headers: Headers, body: RawBody): PutRequest = ???
  override def delete(url: URL, headers: Headers): DeleteRequest = ???
}

class VirtaClientSpec extends FlatSpec with ShouldMatchers {
  implicit val system = ActorSystem("test-virta-system")
  implicit val ec = system.dispatcher
  val c = VirtaConfig()
  val httpClient = new MockHttpClient
  val virtaClient = new VirtaClient(c)(httpClient, ec)

  behavior of "VirtaClient"

  it should "call Virta with provided oppijanumero" in {
    val oppijanumero = "1.2.3"
    val response: Future[HttpResponse] = virtaClient.getOpiskelijanKaikkiTiedot(oppijanumero = Some(oppijanumero))

    response.onComplete(r => {
      r.get.bodyString should include(s"<kansallinenOppijanumero>$oppijanumero</kansallinenOppijanumero>")
    })
  }

  it should "call Virta with provided henkilotunnus" in {
    val hetu = "111111-1975"
    val response: Future[HttpResponse] = virtaClient.getOpiskelijanKaikkiTiedot(hetu = Some(hetu))

    response.onComplete(r => {
      r.get.bodyString should include(s"<henkilotunnus>$hetu</henkilotunnus>")
    })
  }

  it should "wrap the operation in a SOAP envelope" in {
    val response: Future[HttpResponse] = virtaClient.getOpiskelijanKaikkiTiedot(oppijanumero = Some("1.2.3"))

    response.onComplete(r => {
      r.get.bodyString should include("<SOAP-ENV:Envelope")
    })
  }

  it should "throw IllegalArgumentException if no oppijanumero or hetu is provided" in {
    intercept[IllegalArgumentException] {
      virtaClient.getOpiskelijanKaikkiTiedot()
    }
  }

  it should "throw IllegalArgumentException if both oppijanumero and hetu are provided" in {
    intercept[IllegalArgumentException] {
      virtaClient.getOpiskelijanKaikkiTiedot(oppijanumero = Some("1.2.3"), hetu = Some("111111-1975"))
    }
  }

  it should "throw IllegalArgumentException if provided hetu is not valid" in {
    intercept[IllegalArgumentException] {
      virtaClient.getOpiskelijanKaikkiTiedot(hetu = Some("invalid"))
    }
  }
}
