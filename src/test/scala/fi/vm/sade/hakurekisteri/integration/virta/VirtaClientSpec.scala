package fi.vm.sade.hakurekisteri.integration.virta


import akka.actor.ActorSystem
import org.scalatest.{Matchers, FlatSpec}
import org.scalatest.concurrent.AsyncAssertions
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.time.{Millis, Span}

import scala.concurrent.{Await, Future}
import fi.vm.sade.hakurekisteri.integration.{Endpoint, DispatchSupport, CapturingProvider}
import com.ning.http.client.AsyncHttpClient
import org.mockito.Mockito
import org.scalatest.mock.MockitoSugar
import scala.concurrent.duration._

object VirtaResults {
  val emptyResp = scala.io.Source.fromFile("src/test/resources/test-empty-response.xml").mkString

  val multipleStudents = scala.io.Source.fromFile("src/test/resources/test-multiple-students-response.xml").mkString

  val fault = scala.io.Source.fromFile("src/test/resources/test-fault.xml").mkString

  val testResponse = scala.io.Source.fromFile("src/test/resources/test-response.xml").mkString

}



class VirtaClientSpec extends FlatSpec with Matchers with AsyncAssertions with MockitoSugar with DispatchSupport {
  implicit val system = ActorSystem("test-virta-system")
  implicit val ec = system.dispatcher
  import Mockito._

  val endPoint = mock[Endpoint]

  when(endPoint.request(forUrl("http://virtawstesti.csc.fi/luku/OpiskelijanTiedot").withBodyPart("1.2.4"))).thenReturn((200, List(), VirtaResults.emptyResp))
  when(endPoint.request(forUrl("http://virtawstesti.csc.fi/luku/OpiskelijanTiedot").withBodyPart("1.2.5"))).thenReturn((500, List(), "Internal Server Error"))
  when(endPoint.request(forUrl("http://virtawstesti.csc.fi/luku/OpiskelijanTiedot").withBodyPart("1.3.0"))).thenReturn((200, List(), VirtaResults.multipleStudents))
  when(endPoint.request(forUrl("http://virtawstesti.csc.fi/luku/OpiskelijanTiedot").withBodyPart("1.5.0"))).thenReturn((500, List(), VirtaResults.fault))
  when(endPoint.request(forUrl("http://virtawstesti.csc.fi/luku/OpiskelijanTiedot").withBodyPart("1.2.3"))).thenReturn((200, List(), VirtaResults.testResponse))
  when(endPoint.request(forUrl("http://virtawstesti.csc.fi/luku/OpiskelijanTiedot").withBodyPart("111111-1975"))).thenReturn((200, List(), VirtaResults.testResponse))



  val virtaClient = new VirtaClient(aClient = Some(new AsyncHttpClient(new CapturingProvider(endPoint))))

  behavior of "VirtaClient"

  it should "call Virta with provided oppijanumero" in {
    val oppijanumero = "1.2.3"
    val response = virtaClient.getOpiskelijanTiedot(oppijanumero = oppijanumero)

    waitFuture(response) {o => {
      verify(endPoint, atLeastOnce()).request(forUrl("http://virtawstesti.csc.fi/luku/OpiskelijanTiedot").withBodyPart(s"<kansallinenOppijanumero>$oppijanumero</kansallinenOppijanumero>"))
      //httpClient.capturedRequestBody should include(s"<kansallinenOppijanumero>$oppijanumero</kansallinenOppijanumero>")
    }}
  }

  it should "call Virta with provided henkilotunnus" in {
    val hetu = "111111-1975"
    val response = virtaClient.getOpiskelijanTiedot(hetu = Some(hetu), oppijanumero = "1.2.3")

    waitFuture(response) {o => {
      verify(endPoint, atLeastOnce()).request(forUrl("http://virtawstesti.csc.fi/luku/OpiskelijanTiedot").withBodyPart(s"<henkilotunnus>$hetu</henkilotunnus>"))

    }}
  }

  it should "wrap the operation in a SOAP envelope" in {
    val response = virtaClient.getOpiskelijanTiedot(oppijanumero = "1.2.3")

    waitFuture(response) {o => {
      verify(endPoint, atLeastOnce()).request(forUrl("http://virtawstesti.csc.fi/luku/OpiskelijanTiedot").withBodyPart("<SOAP-ENV:Envelope"))

    }}
  }

  it should "return student information" in {
    val response: Future[Option[VirtaResult]] = virtaClient.getOpiskelijanTiedot(oppijanumero = "1.2.3")

    waitFuture(response) {o => {
      o.get.opiskeluoikeudet.size should be(2)
      o.get.tutkinnot.size should be(1)
    }}
  }

  it should "return None if no data is found" in {
    val response: Future[Option[VirtaResult]] = virtaClient.getOpiskelijanTiedot(oppijanumero = "1.2.4")

    waitFuture(response) {(o: Option[VirtaResult]) => {
      o should be(None)
    }}
  }

  it should "combine multiple student records into one opiskeluoikeus sequence" in {
    val response: Future[Option[VirtaResult]] = virtaClient.getOpiskelijanTiedot(oppijanumero = "1.3.0")

    waitFuture(response) {(o: Option[VirtaResult]) => {
      o.get.opiskeluoikeudet.size should be(3)
      o.get.tutkinnot.size should be(3)
    }}
  }

  it should "throw VirtaConnectionErrorException if an error occurred" in {

    intercept[VirtaConnectionErrorException] {
      val response = virtaClient.getOpiskelijanTiedot(oppijanumero = "1.2.5")
      Await.result(response, 10.seconds)

    }
  }

  it should "throw VirtaValidationError if validation error was returned" in {
    val response = virtaClient.getOpiskelijanTiedot(oppijanumero = "1.5.0")

    intercept[VirtaValidationError] {
      Await.result(response, 10.seconds)
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


}
