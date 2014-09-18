package fi.vm.sade.hakurekisteri.integration.virta

import java.net.URL
import java.nio.charset.Charset
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
    capturedRequestBody match {
      case s: String if s.contains("1.2.4") => Future.successful(HttpResponse(HttpResponseCode.Ok, Headers(List()), RawBody(scala.io.Source.fromFile("src/test/resources/test-empty-response.xml").mkString), new Date()))
      case s: String if s.contains("1.2.5") => Future.successful(HttpResponse(HttpResponseCode.InternalServerError, Headers(List()), RawBody("Infernal server error", Charset.forName("UTF-8")), new Date()))
      case s: String if s.contains("1.3.0") => Future.successful(HttpResponse(HttpResponseCode.Ok, Headers(List()), RawBody(scala.io.Source.fromFile("src/test/resources/test-multiple-students-response.xml").mkString), new Date()))
      case _ => Future.successful(HttpResponse(HttpResponseCode.Ok, Headers(List()), RawBody(scala.io.Source.fromFile("src/test/resources/test-response.xml").mkString), new Date()))
    }
  }
  override def head(url: URL, headers: Headers): HeadRequest = ???
  override def get(url: URL, headers: Headers): GetRequest = ???
  override def put(url: URL, headers: Headers, body: RawBody): PutRequest = ???
  override def delete(url: URL, headers: Headers): DeleteRequest = ???
}

class VirtaClientSpec extends FlatSpec with ShouldMatchers with AsyncAssertions {
  implicit val system = ActorSystem("test-virta-system")
  implicit val ec = system.dispatcher
  implicit val httpClient = new MockHttpClient
  val virtaClient = new VirtaClient

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
    val response: Future[Option[VirtaResult]] = virtaClient.getOpiskelijanTiedot(oppijanumero = Some("1.2.3"))

    waitFuture(response) {o => {
      o.get.opiskeluoikeudet.size should be(2)
      o.get.tutkinnot.size should be(1)
    }}
  }

  it should "return None if no data is found" in {
    val response: Future[Option[VirtaResult]] = virtaClient.getOpiskelijanTiedot(oppijanumero = Some("1.2.4"))

    waitFuture(response) {(o: Option[VirtaResult]) => {
      o should be(None)
    }}
  }

  it should "combine multiple student records into one opiskeluoikeus sequence" in {
    val response: Future[Option[VirtaResult]] = virtaClient.getOpiskelijanTiedot(oppijanumero = Some("1.3.0"))

    waitFuture(response) {(o: Option[VirtaResult]) => {
      o.get.opiskeluoikeudet.size should be(3)
      o.get.tutkinnot.size should be(3)
    }}
  }

  it should "throw VirtaConnectionErrorException if an connection error occurred" in {
    val response = virtaClient.getOpiskelijanTiedot(oppijanumero = Some("1.2.5"))

    intercept[VirtaConnectionErrorException] {
      waitFutureFailure(response)
    }
  }

  it should "throw IllegalArgumentException if no oppijanumero or hetu is provided" in {
    intercept[IllegalArgumentException] {
      waitFutureFailure(virtaClient.getOpiskelijanTiedot())
    }
  }

  it should "throw IllegalArgumentException if provided hetu is not valid" in {
    intercept[IllegalArgumentException] {
      waitFutureFailure(virtaClient.getOpiskelijanTiedot(hetu = Some("invalid"), oppijanumero = Some("1.2.3")))
    }
  }

  def waitFuture[A](f: Future[A])(assertion: A => Unit) = {
    val w = new Waiter

    f.onComplete(r => {
      w(assertion(r.get))
      w.dismiss()
    })

    w.await(timeout(Span(5000, Millis)), dismissals(1))
  }

  def waitFutureFailure[A](f: Future[A]) {
    val w = new Waiter
    
    f.onComplete {
      case Failure(e) => w(throw e); w.dismiss()
      case Success(s) => w.dismiss()
    }
    
    w.await(timeout(Span(5000, Millis)), dismissals(1))
  }
}
