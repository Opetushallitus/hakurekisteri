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
  val hakemusService = new RemoteHakemusService(client)
  val jsonDir = "src/test/scala/fi/vm/sade/hakurekisteri/integration/hakemus/json/"

  def getJson(testCase: String) : String = scala.io.Source.fromFile(jsonDir + testCase + ".json").mkString

  it should "return applications by person oid" in {
    when(endPoint.request(forPattern(".*applications/byPersonOid.*")))
      .thenReturn((200, List(), getJson("applicationsByPersonOid")))

    hakemusService.hakemuksetByPerson("1.2.246.562.24.81468276424") should be (2)
  }



}