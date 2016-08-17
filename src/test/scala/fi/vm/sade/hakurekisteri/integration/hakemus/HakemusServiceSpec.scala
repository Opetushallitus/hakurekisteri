package fi.vm.sade.hakurekisteri.integration.hakemus

import akka.actor.ActorSystem
import com.ning.http.client.AsyncHttpClient
import fi.vm.sade.hakurekisteri.integration._
import org.mockito.Mockito._
import org.scalatest._
import org.scalatest.mock.MockitoSugar

import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

class HakemusServiceSpec extends FlatSpec with Matchers with MockitoSugar with DispatchSupport with LocalhostProperties {

  implicit val system = ActorSystem(s"test-system-${Platform.currentTime.toString}")
  implicit def executor: ExecutionContext = system.dispatcher
  val endPoint = mock[Endpoint]
  val asyncProvider = new CapturingProvider(endPoint)
  val client = new VirkailijaRestClient(ServiceConfig(serviceUrl = "http://localhost/haku-app"), aClient = Some(new AsyncHttpClient(asyncProvider)))
  val hakemusService = new HakemusService(restClient = client, pageSize = 10)

  it should "return applications by person oid" in {
    when(endPoint.request(forPattern(".*applications/byPersonOid.*")))
      .thenReturn((200, List(), getJson("applicationsByPersonOid")))

    Await.result(hakemusService.hakemuksetForPerson("1.2.246.562.24.81468276424"), 10.seconds).size should be (2)
  }

  it should "return applications when searching with both persons and application system" in {
    when(endPoint.request(forPattern(".*applications/byPersonOid.*")))
      .thenReturn((200, List(), getJson("applicationsByPersonOidsAndHaku")))

    val persons = Set("1.2.246.562.24.62737906266", "1.2.246.562.24.99844104050")
    val applicationSystem = "1.2.246.562.29.90697286251"
    val res = Await.result(hakemusService.hakemuksetForPersonsInHaku(persons, applicationSystem), 10.seconds)
    res.size should be(2)

    res.foreach(application => {
      application.applicationSystemId should be(applicationSystem)
      persons.contains(application.personOid.get) should be(true)
    })
  }

  it should "return applications by application option oid" in {
    when(endPoint.request(forPattern(".*applications/byApplicationOption.*")))
      .thenReturn((200, List(), getJson("byApplicationOption")))

    Await.result(hakemusService.hakemuksetForHakukohde("1.2.246.562.20.649956391810", None), 10.seconds).size should be (6)
  }

  it should "execute trigger function for modified applications" in {
    val system = ActorSystem("hakurekisteri")
    implicit val scheduler = system.scheduler

    when(endPoint.request(forPattern(".*listfull.*start=0.*")))
      .thenReturn((200, List(), getJson("listfull-0")))
      .thenReturn((200, List(), "[]"))
    when(endPoint.request(forPattern(".*listfull.*start=1.*")))
      .thenReturn((200, List(), getJson("listfull-1")))
    when(endPoint.request(forPattern(".*listfull.*start=2.*")))
      .thenReturn((200, List(), "[]"))


    var triggerCounter = 0
    val trigger = Trigger(f = (hakemus: FullHakemus) => {
      triggerCounter += 1
    })

    hakemusService.addTrigger(trigger)
    hakemusService.addTrigger(trigger)

    hakemusService.processModifiedHakemukset(refreshFrequency = 1.millisecond)

    Thread.sleep(1000)

    triggerCounter should be (40)
  }

}