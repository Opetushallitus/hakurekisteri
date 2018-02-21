package fi.vm.sade.hakurekisteri.integration.koski

import akka.actor.ActorSystem
import com.ning.http.client.AsyncHttpClient
import fi.vm.sade.hakurekisteri.integration._
import fi.vm.sade.hakurekisteri.integration.henkilo.MockOppijaNumeroRekisteri
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
  val koskiService = new KoskiService(virkailijaRestClient = client, oppijaNumeroRekisteri = MockOppijaNumeroRekisteri, pageSize = 10)
  override val jsonDir = "src/test/scala/fi/vm/sade/hakurekisteri/integration/koski/json/"

  it should "return suoritukset" in {
    when(endPoint.request(forUrl("http://localhost/koski/api/oppija?muuttunutJ%C3%A4lkeen=2010-01-01")))
      .thenReturn((200, List(), getJson("koski_1130")))
    Await.result(koskiService.fetchChanged(0, koskiService.SearchParams(muuttunutJälkeen = "2010-01-01")), 10.seconds).size should be (3)
  }
}
