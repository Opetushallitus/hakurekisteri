package fi.vm.sade.hakurekisteri.integration.virta

import java.util.concurrent.TimeUnit
import akka.actor.ActorSystem
import fi.vm.sade.hakurekisteri.integration.{
  CapturingAsyncHttpClient,
  DispatchSupport,
  Endpoint,
  ExecutorUtil
}
import fi.vm.sade.hakurekisteri.test.tools.FutureWaiting
import org.joda.time.LocalDate
import org.mockito.Mockito
import org.scalatest.concurrent.Waiters
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

object VirtaResults {

  val emptyResp = scala.io.Source.fromURL(getClass.getResource("/test-empty-response.xml")).mkString

  val multipleStudents =
    scala.io.Source.fromURL(getClass.getResource("/test-multiple-students-response.xml")).mkString

  val fault = scala.io.Source.fromURL(getClass.getResource("/test-fault.xml")).mkString

  val testResponse = scala.io.Source.fromURL(getClass.getResource("/test-response.xml")).mkString

  val opiskeluoikeustyypit = scala.io.Source
    .fromURL(getClass.getResource("/test-response-opiskeluoikeustyypit.xml"))
    .mkString

  val testResponse106 =
    scala.io.Source.fromURL(getClass.getResource("/virta/test-response-106.xml")).mkString

  val testResponseOpiskeluoikeusFilter =
    scala.io.Source
      .fromURL(getClass.getResource("/virta/test-response-opiskeluoikeus-filter.xml"))
      .mkString

}

class VirtaClientSpec
    extends AnyFlatSpec
    with Matchers
    with Waiters
    with MockitoSugar
    with DispatchSupport
    with FutureWaiting
    with BeforeAndAfterAll {
  implicit val system = ActorSystem("test-virta-system")
  implicit val clientEc = ExecutorUtil.createExecutor(1, "virta-test-pool")
  import Mockito._

  override def afterAll() = {
    clientEc.shutdown()
    clientEc.awaitTermination(3, TimeUnit.SECONDS)
  }

  val endPoint = mock[Endpoint]

  when(
    endPoint.request(
      forUrl("http://virtawstesti.csc.fi/luku/OpiskelijanTiedot").withBodyPart("1.2.4")
    )
  ).thenReturn((200, List(), VirtaResults.emptyResp))
  when(
    endPoint.request(
      forUrl("http://virtawstesti.csc.fi/luku/OpiskelijanTiedot").withBodyPart("1.2.5")
    )
  ).thenReturn((500, List(), "Internal Server Error"))
  when(
    endPoint.request(
      forUrl("http://virtawstesti.csc.fi/luku/OpiskelijanTiedot").withBodyPart("1.3.0")
    )
  ).thenReturn((200, List(), VirtaResults.multipleStudents))
  when(
    endPoint.request(
      forUrl("http://virtawstesti.csc.fi/luku/OpiskelijanTiedot").withBodyPart("1.5.0")
    )
  ).thenReturn((500, List(), VirtaResults.fault))
  when(
    endPoint.request(
      forUrl("http://virtawstesti.csc.fi/luku/OpiskelijanTiedot").withBodyPart("1.2.3")
    )
  ).thenReturn((200, List(), VirtaResults.testResponse))
  when(
    endPoint.request(
      forUrl("http://virtawstesti.csc.fi/luku/OpiskelijanTiedot").withBodyPart("1.2.9")
    )
  ).thenReturn((200, List(), VirtaResults.opiskeluoikeustyypit))
  when(
    endPoint.request(
      forUrl("http://virtawstesti.csc.fi/luku/OpiskelijanTiedot").withBodyPart("111111-1975")
    )
  ).thenReturn((200, List(), VirtaResults.testResponse))
  when(
    endPoint.request(
      forUrl("http://virtawstesti.csc.fi/luku/OpiskelijanTiedot").withBodyPart("1.2.106")
    )
  ).thenReturn((200, List(), VirtaResults.testResponse106))

  when(
    endPoint.request(
      forUrl("http://virtawstesti.csc.fi/luku/OpiskelijanTiedot").withBodyPart("040501A953L")
    )
  ).thenReturn((200, List(), VirtaResults.testResponseOpiskeluoikeusFilter))

  val virtaClient = new VirtaClient(aClient = Some(new CapturingAsyncHttpClient(endPoint)))

  behavior of "VirtaClient"

  it should "call Virta with provided oppijanumero" in {
    val oppijanumero = "1.2.3"
    val response = virtaClient.getOpiskelijanTiedot(oppijanumero = oppijanumero)

    waitFuture(response) { o =>
      {
        verify(endPoint, atLeastOnce()).request(
          forUrl("http://virtawstesti.csc.fi/luku/OpiskelijanTiedot").withBodyPart(
            s"<kansallinenOppijanumero>$oppijanumero</kansallinenOppijanumero>"
          )
        )
        //httpClient.capturedRequestBody should include(s"<kansallinenOppijanumero>$oppijanumero</kansallinenOppijanumero>")
      }
    }
  }

  it should "call Virta with provided henkilotunnus" in {
    val hetu = "111111-1975"
    val response = virtaClient.getOpiskelijanTiedot(hetu = Some(hetu), oppijanumero = "1.2.3")

    waitFuture(response) { o =>
      {
        verify(endPoint, atLeastOnce()).request(
          forUrl("http://virtawstesti.csc.fi/luku/OpiskelijanTiedot").withBodyPart(
            s"<henkilotunnus>$hetu</henkilotunnus>"
          )
        )
      }
    }
  }

  it should "wrap the operation in a SOAP envelope" in {
    val response = virtaClient.getOpiskelijanTiedot(oppijanumero = "1.2.3")

    waitFuture(response) { o =>
      {
        verify(endPoint, atLeastOnce()).request(
          forUrl("http://virtawstesti.csc.fi/luku/OpiskelijanTiedot").withBodyPart(
            "<SOAP-ENV:Envelope"
          )
        )
      }
    }
  }

  it should "attach Content-Type: text/xml header into the request" in {
    val response = virtaClient.getOpiskelijanTiedot(oppijanumero = "1.2.3")

    waitFuture(response) { o =>
      {
        verify(endPoint, atLeastOnce()).request(
          forUrl("http://virtawstesti.csc.fi/luku/OpiskelijanTiedot")
            .withHeader("Content-Type", "text/xml; charset=UTF-8")
        )
      }
    }
  }

  it should "return student information" in {
    val response: Future[Option[VirtaResult]] =
      virtaClient.getOpiskelijanTiedot(oppijanumero = "1.2.3")

    waitFuture(response) { o =>
      {
        o.get.opiskeluoikeudet.size should be(1)
        o.get.tutkinnot.size should be(1)
        o.get.suoritukset.size should be(7)
      }
    }
  }

  it should "return empty Seqs if no data is found" in {
    val response: Future[Option[VirtaResult]] =
      virtaClient.getOpiskelijanTiedot(oppijanumero = "1.2.4")

    waitFuture(response) { (o: Option[VirtaResult]) =>
      {
        o.get.opiskeluoikeudet should be(Seq())
        o.get.tutkinnot should be(Seq())
        o.get.suoritukset should be(Seq())
      }
    }
  }

  it should "combine multiple student records into one opiskeluoikeus sequence" in {
    val response: Future[Option[VirtaResult]] =
      virtaClient.getOpiskelijanTiedot(oppijanumero = "1.3.0")

    waitFuture(response) { (o: Option[VirtaResult]) =>
      {
        o.get.opiskeluoikeudet.size should be(3)
        o.get.tutkinnot.size should be(3)
      }
    }
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

  it should "parse only opiskeluoikeustyypit 1, 2, 3, 4, 6 and 7" in {
    val response = virtaClient.getOpiskelijanTiedot(oppijanumero = "1.2.9")

    waitFuture(response)((o: Option[VirtaResult]) => {
      o.get.opiskeluoikeudet.size should be(1)
    })
  }

  it should "parse local date with timezone" in {
    virtaClient.parseLocalDate("2011-08-10+03:00") should be(new LocalDate(2011, 8, 10))
  }

  it should "throw IllegalArgumentException with invalid date" in {
    intercept[IllegalArgumentException] {
      virtaClient.parseLocalDate("2011-08-10+foo")
    }
  }

  it should "parse local date with timezone to some" in {
    virtaClient.parseLocalDateOption(Some("2011-08-10+03:00")) should be(
      Some(new LocalDate(2011, 8, 10))
    )
  }

  it should "throw exception when parsing local date with invalid timezone" in {
    intercept[IllegalArgumentException] {
      virtaClient.parseLocalDateOption(Some("2011-08-10+foo")) should be(None)
    }
  }

  it should "parse empty date to None" in {
    virtaClient.parseLocalDateOption(Some("")) should be(None)
  }

  it should "pass None as None" in {
    virtaClient.parseLocalDateOption(None) should be(None)
  }

  it should "work with version 1.06 schema" in {
    virtaClient.setApiVersion(VirtaClient.version106)

    val response: Future[Option[VirtaResult]] =
      virtaClient.getOpiskelijanTiedot(oppijanumero = "1.2.106")

    waitFuture(response) { (o: Option[VirtaResult]) =>
      {
        o.get.opiskeluoikeudet.size should be(5)
        o.get.tutkinnot.size should be(1)
      }
    }
  }

  it should "parse arvosana with asteikko Muu correctly" in {
    virtaClient.setApiVersion(VirtaClient.version106)

    val response: Future[Option[VirtaResult]] =
      virtaClient.getOpiskelijanTiedot(oppijanumero = "1.2.106")

    waitFuture(response) { (o: Option[VirtaResult]) =>
      {
        o.map(_.suoritukset)
          .exists(
            _.exists(s =>
              s.arvosana == Some("4") && s.asteikko == Some("Meidän oma asteikko yhdestä neljään")
            )
          ) should be(true)
      }
    }
  }

  it should "return only opiskeluoikeudet which YPS needs" in {
    virtaClient.setApiVersion(VirtaClient.version106)

    val response: Future[Option[VirtaResult]] =
      virtaClient.getOpiskelijanTiedot(hetu = Some("1040501A953L"), oppijanumero = "")

    waitFuture(response) { (result: Option[VirtaResult]) =>
      val opiskeluoikeudet: Seq[VirtaOpiskeluoikeus] = result.map(_.opiskeluoikeudet).get
      opiskeluoikeudet.size should be(4)
    }
  }
}
