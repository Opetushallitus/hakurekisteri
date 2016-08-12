package fi.vm.sade.hakurekisteri.integration.hakemus

import akka.actor.ActorSystem
import com.ning.http.client.AsyncHttpClient
import fi.vm.sade.hakurekisteri.integration._
import org.mockito.Mockito._
import org.scalatest._
import org.scalatest.mock.MockitoSugar

import scala.compat.Platform
import scala.concurrent.ExecutionContext

class HakemusServiceSpec extends FlatSpec with Matchers with MockitoSugar with DispatchSupport with LocalhostProperties {

  implicit val system = ActorSystem(s"test-system-${Platform.currentTime.toString}")
  implicit def executor: ExecutionContext = system.dispatcher
  val endPoint = mock[Endpoint]
  val asyncProvider = new CapturingProvider(endPoint)
  val client = new VirkailijaRestClient(ServiceConfig(serviceUrl = "http://localhost/haku-app"), aClient = Some(new AsyncHttpClient(asyncProvider)))
  val hakemusService = new HakemusService(client)

  it should "return applications by person oid" in {
    when(endPoint.request(forPattern(".*applications/byPersonOid.*")))
      .thenReturn((200, List(), getJson("applicationsByPersonOid")))

    hakemusService.hakemuksetForPerson("1.2.246.562.24.81468276424").size should be (2)
  }

  it should "return applications by application option oid" in {
    when(endPoint.request(forPattern(".*applications/byApplicationOption.*")))
      .thenReturn((200, List(), getJson("byApplicationOption")))

    hakemusService.hakemuksetForHakukohde("1.2.246.562.20.649956391810").size should be (6)
  }

}