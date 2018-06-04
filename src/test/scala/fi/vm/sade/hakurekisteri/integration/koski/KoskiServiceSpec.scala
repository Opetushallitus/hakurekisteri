package fi.vm.sade.hakurekisteri.integration.koski

import java.text.DateFormat
import java.time.format.DateTimeFormatter
import java.util.{Date, TimeZone}
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import com.ning.http.client.AsyncHttpClient
import fi.vm.sade.hakurekisteri.integration._
import fi.vm.sade.hakurekisteri.integration.hakemus.HakemusServiceMock
import fi.vm.sade.hakurekisteri.integration.henkilo.MockOppijaNumeroRekisteri
import org.joda.time.{DateTime, DateTimeZone}
import org.mockito.Mockito._
import org.scalatest._
import org.scalatest.mock.MockitoSugar

import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

class KoskiServiceSpec extends FlatSpec with Matchers with MockitoSugar with DispatchSupport with LocalhostProperties {

  implicit val system = ActorSystem(s"test-system-${Platform.currentTime.toString}")
  implicit def executor: ExecutionContext = system.dispatcher
  val endPoint = mock[Endpoint]
  val asyncProvider = new CapturingProvider(endPoint)
  val client = new VirkailijaRestClient(ServiceConfig(serviceUrl = "http://localhost/koski"), aClient = Some(new AsyncHttpClient(asyncProvider)))
  val koskiService = new KoskiService(virkailijaRestClient = client, oppijaNumeroRekisteri = MockOppijaNumeroRekisteri, pageSize = 10, hakemusService = new HakemusServiceMock())
  override val jsonDir = "src/test/scala/fi/vm/sade/hakurekisteri/integration/koski/json/"

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
