package fi.vm.sade.hakurekisteri.integration.koski

import java.text.DateFormat
import java.time.format.DateTimeFormatter
import java.util.{Date, TimeZone}
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorSystem}
import akka.testkit.TestActorRef
import com.ning.http.client.AsyncHttpClient
import fi.vm.sade.hakurekisteri.{Config, MockConfig}
import fi.vm.sade.hakurekisteri.integration._
import fi.vm.sade.hakurekisteri.integration.hakemus.HakemusServiceMock
import fi.vm.sade.hakurekisteri.integration.henkilo.MockOppijaNumeroRekisteri
import fi.vm.sade.hakurekisteri.integration.valintarekisteri.ValintarekisteriQuery
import org.joda.time.{DateTime, DateTimeZone}
import org.mockito.Mockito._
import org.scalatest._
import org.scalatest.mock.MockitoSugar

import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Success, Try}

class KoskiServiceSpec extends FlatSpec with Matchers with MockitoSugar with DispatchSupport with LocalhostProperties {

  implicit val system = ActorSystem(s"test-system-${Platform.currentTime.toString}")
  implicit def executor: ExecutionContext = system.dispatcher
  val endPoint = mock[Endpoint]
  val asyncProvider = new CapturingProvider(endPoint)
  val client = new VirkailijaRestClient(ServiceConfig(serviceUrl = "http://localhost/koski"), aClient = Some(new AsyncHttpClient(asyncProvider)))
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

  it should "return successful future for handleHenkiloUpdate" in {
    when(endPoint.request(forUrl("http://localhost/koski/api/sure/oids")))
      .thenReturn((200, List(), "[]"))
    val future = koskiService.handleHenkiloUpdate(Seq("1.2.3.4"), new KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = true))
    Await.result(future, 10.seconds)
  }
}
