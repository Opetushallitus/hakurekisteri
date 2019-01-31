package fi.vm.sade.hakurekisteri.integration.koski

import java.util.concurrent.TimeUnit
import java.util.{Date, TimeZone}

import akka.actor.{Actor, ActorSystem}
import akka.testkit.TestActorRef
import fi.vm.sade.hakurekisteri.integration._
import fi.vm.sade.hakurekisteri.integration.hakemus.HakemusServiceMock
import fi.vm.sade.hakurekisteri.integration.henkilo.MockOppijaNumeroRekisteri
import org.joda.time.{DateTime, DateTimeZone}
import org.mockito.Mockito._
import org.scalatest._
import org.scalatest.mockito.MockitoSugar

import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

class KoskiServiceSpec extends FlatSpec with Matchers with MockitoSugar with DispatchSupport with LocalhostProperties {

  implicit val system = ActorSystem(s"test-system-${Platform.currentTime.toString}")
  implicit def executor: ExecutionContext = system.dispatcher
  val endPoint = mock[Endpoint]
  val client = new VirkailijaRestClient(ServiceConfig(serviceUrl = "http://localhost/koski"), aClient = Some(new CapturingAsyncHttpClient(endPoint)))
  val testRef = TestActorRef(new Actor {
    override def receive: Actor.Receive = {
      case q =>
        sender ! Seq()
    }
  })
  val arvosanaHandler: KoskiArvosanaHandler = new KoskiArvosanaHandler(testRef, testRef, testRef)
  val koskiService = new KoskiService(virkailijaRestClient = client,
    oppijaNumeroRekisteri = MockOppijaNumeroRekisteri, pageSize = 10,
    hakemusService = new HakemusServiceMock(),
    koskiArvosanaHandler = arvosanaHandler)

  override val jsonDir = "src/test/scala/fi/vm/sade/hakurekisteri/integration/koski/json/"

  it should "return successful future for handleHenkiloUpdate" in {
    when(endPoint.request(forUrl("http://localhost/koski/api/sure/oids")))
      .thenReturn((200, List(), "[]"))
    val future = koskiService.handleHenkiloUpdate(Seq("1.2.3.4"), createLukio = true)
    Await.result(future, 10.seconds)
  }

  it should "return suoritukset" in {
    when(endPoint.request(forUrl("http://localhost/koski/api/oppija?muuttunutJ%C3%A4lkeen=2010-01-01&muuttunutEnnen=2100-01-01T12%3A00")))
      .thenReturn((200, List(), getJson("koski_1130")))
    Await.result(koskiService.fetchChanged(0, koskiService.SearchParams(muuttunutJälkeen = "2010-01-01")), 10.seconds).size should be (3)
  }

  it should "clamp search window time to endDateSuomiTime in KoskiService" in {

    val HelsinkiTimeZone = TimeZone.getTimeZone("Europe/Helsinki")
    val endDateSuomiTime = DateTime.parse("2018-06-05T18:00:00").withZoneRetainFields(DateTimeZone.forTimeZone(HelsinkiTimeZone))
    val queryTime = DateTime.parse("2018-05-28T00:00:00").withZoneRetainFields(DateTimeZone.forTimeZone(HelsinkiTimeZone)).toDate
    val searchWindowStartTime: Date = new Date(queryTime.getTime- TimeUnit.DAYS.toMillis(1))
    val searchWindowSize: Long = TimeUnit.DAYS.toMillis(15)
    val searchWindowEndTime: Date = new Date(searchWindowStartTime.getTime + searchWindowSize)
    val end: Date = koskiService.clampTimeToEnd(searchWindowEndTime)

    end shouldEqual endDateSuomiTime.toDate
  }

  it should "not clamp time if window begin time is early enough" in {
    val HelsinkiTimeZone = TimeZone.getTimeZone("Europe/Helsinki")
    val endDateSuomiTime = DateTime.parse("2018-05-15T00:00:00").withZoneRetainFields(DateTimeZone.forTimeZone(HelsinkiTimeZone))
    val queryTime = DateTime.parse("2018-05-01T00:00:00").withZoneRetainFields(DateTimeZone.forTimeZone(HelsinkiTimeZone)).toDate

    val searchWindowStartTime: Date = new Date(queryTime.getTime- TimeUnit.DAYS.toMillis(1))

    val searchWindowSize: Long = TimeUnit.DAYS.toMillis(15)
    val searchWindowEndTime: Date = new Date(searchWindowStartTime.getTime + searchWindowSize)

    val end: Date = koskiService.clampTimeToEnd(searchWindowEndTime)

    end shouldEqual endDateSuomiTime.toDate
  }
}
