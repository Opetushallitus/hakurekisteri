package fi.vm.sade.hakurekisteri.virta

import java.net.URL
import java.util.Date

import akka.actor.ActorSystem
import com.stackmob.newman.request._
import com.stackmob.newman.response.{HttpResponseCode, HttpResponse}
import com.stackmob.newman.{RawBody, Headers, HttpClient}
import org.scalatest.FlatSpec
import org.scalatest.concurrent.AsyncAssertions
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.time.{Millis, Span}

import scala.concurrent.Future
import scala.util.{Success, Failure}

class MockHttpClient extends HttpClient {
  var capturedRequestBody: String = ""
  override def post(url: URL, headers: Headers, body: RawBody): PostRequest = PostRequest(url, headers, body) {
    capturedRequestBody = new String(body)
    Future.successful(HttpResponse(HttpResponseCode.Ok, Headers(List()), RawBody(scala.io.Source.fromFile("src/test/resources/test-response.xml").mkString), new Date()))
  }
  override def head(url: URL, headers: Headers): HeadRequest = ???
  override def get(url: URL, headers: Headers): GetRequest = ???
  override def put(url: URL, headers: Headers, body: RawBody): PutRequest = ???
  override def delete(url: URL, headers: Headers): DeleteRequest = ???
}

class VirtaClientSpec extends FlatSpec with ShouldMatchers with AsyncAssertions {
  implicit val system = ActorSystem("test-virta-system")
  implicit val ec = system.dispatcher
  val c = VirtaConfig()
  val httpClient = new MockHttpClient
  val virtaClient = new VirtaClient(c)(httpClient, ec)

  behavior of "VirtaClient"

  it should "call Virta with provided oppijanumero" in {
    val oppijanumero = "1.2.3"
    val response = virtaClient.getOpiskelijanTiedot(oppijanumero = Some(oppijanumero))

    waitFuture(response) {o => {
      httpClient.capturedRequestBody should include(s"<kansallinenOppijanumero>$oppijanumero</kansallinenOppijanumero>")
    }}
  }

  it should "call Virta with provided henkilotunnus" in {
    val hetu = "111111-1975"
    val response = virtaClient.getOpiskelijanTiedot(hetu = Some(hetu), oppijanumero = Some("1.2.3"))

    waitFuture(response) {o => {
      httpClient.capturedRequestBody should include(s"<henkilotunnus>$hetu</henkilotunnus>")
    }}
  }

  it should "wrap the operation in a SOAP envelope" in {
    val response = virtaClient.getOpiskelijanTiedot(oppijanumero = Some("1.2.3"))

    waitFuture(response) {o => {
      httpClient.capturedRequestBody should include("<SOAP-ENV:Envelope")
    }}
  }

  it should "return student information" in {
    val response: Future[Option[OpiskelijanTiedot]] = virtaClient.getOpiskelijanTiedot(oppijanumero = Some("1.2.3"))

    waitFuture(response) {o => {
      o.get.suoritukset.size should be(3)
    }}
  }

  it should "throw IllegalArgumentException if no oppijanumero or hetu is provided" in {
    intercept[IllegalArgumentException] {
      waitFutureFailure(virtaClient.getOpiskelijanTiedot()).await
    }
  }

  it should "throw IllegalArgumentException if provided hetu is not valid" in {
    intercept[IllegalArgumentException] {
      waitFutureFailure(virtaClient.getOpiskelijanTiedot(hetu = Some("invalid"), oppijanumero = Some("1.2.3"))).await
    }
  }

  def waitFuture[A](f: Future[A])(assertion: A => Unit) = {
    val w = new Waiter

    f.onComplete(r => {
      w(assertion(r.get))
      w.dismiss()
    })

    w.await(timeout(Span(1000, Millis)), dismissals(1))
  }

  def waitFutureFailure[A](f: Future[A]): Waiter = {
    val w = new Waiter
    
    f.onComplete {
      case Failure(e) => w(throw e); w.dismiss()
      case Success(_) => w.dismiss()
    }
    
    w
  }
}
