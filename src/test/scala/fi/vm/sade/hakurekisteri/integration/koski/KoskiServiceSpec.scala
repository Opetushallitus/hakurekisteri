package fi.vm.sade.hakurekisteri.integration.koski

import akka.actor.ActorSystem
import com.ning.http.client.AsyncHttpClient
import fi.vm.sade.hakurekisteri.integration._
import fi.vm.sade.hakurekisteri.integration.henkilo.{MockOppijaNumeroRekisteri, PersonOidsWithAliases}
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
    when(endPoint.request(forPattern("http://localhost/koski/api/oppija")))
      .thenReturn((200, List(), getJson("koski_1130")))
    println(koskiService.fetchChanged())
    Await.result(koskiService.fetchChanged(), 10.seconds).size should be (3)
  }
}
