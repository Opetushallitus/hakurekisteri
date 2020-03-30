package fi.vm.sade.hakurekisteri.integration.koski

import akka.actor.{Actor, ActorSystem}
import akka.testkit.TestActorRef
import fi.vm.sade.hakurekisteri.MockConfig
import fi.vm.sade.hakurekisteri.integration._
import fi.vm.sade.hakurekisteri.integration.hakemus.HakemusServiceMock
import fi.vm.sade.hakurekisteri.integration.henkilo.MockOppijaNumeroRekisteri
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
  val koskiDataHandler: KoskiDataHandler = new KoskiDataHandler(testRef, testRef, testRef)
  val koskiService = new KoskiService(virkailijaRestClient = client,
    oppijaNumeroRekisteri = MockOppijaNumeroRekisteri, pageSize = 10,
    hakemusService = new HakemusServiceMock(),
    koskiDataHandler = koskiDataHandler,
    config = new MockConfig)


  override val jsonDir = "src/test/scala/fi/vm/sade/hakurekisteri/integration/koski/json/"

  it should "retry on occasional errors when updating henkilot for haku" in {
    val params = KoskiSuoritusHakuParams(saveLukio = false, saveAmmatillinen = true)
    val numeros = Range(1, 12345).map(n => s"1.2.3.$n")
    when(endPoint.request(forUrl("http://localhost/koski/api/sure/oids")))
      .thenReturn((200, List(), "[]"), (200, List(), "[]"), (200, List(), "[]"), (200, List(), "[]"), (200, List(), "[]"), (200, List(), "[]"))
      .thenThrow(new RuntimeException("the first surprising failure!"))
      .thenReturn((200, List(), "[]"), (200, List(), "[]"), (200, List(), "[]"), (200, List(), "[]"), (200, List(), "[]"), (200, List(), "[]"))
      .thenThrow(new RuntimeException("another one fails!"))
      .thenThrow(new RuntimeException("another one fails, 2 in a row!"))
      .thenThrow(new RuntimeException("another one fails, 3 in a row!"))
      .thenReturn((200, List(), "[]"))
    val future = koskiService.handleHenkiloUpdate(numeros, params, "just testing!")
    Await.result(future, 10.seconds)
  }

  it should "return successful future for handleHenkiloUpdate" in {
    when(endPoint.request(forUrl("http://localhost/koski/api/sure/oids")))
      .thenReturn((200, List(), "[]"))
    val future = koskiService.handleHenkiloUpdate(Seq("1.2.3.4"), new KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = true), "just testing!")
    Await.result(future, 10.seconds)
  }

}
