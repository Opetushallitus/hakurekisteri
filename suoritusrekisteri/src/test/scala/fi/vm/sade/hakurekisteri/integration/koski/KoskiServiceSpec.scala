package fi.vm.sade.hakurekisteri.integration.koski

import akka.actor.{Actor, ActorSystem}
import akka.testkit.TestActorRef
import fi.vm.sade.hakurekisteri.{MockCacheFactory, MockConfig}
import fi.vm.sade.hakurekisteri.integration._
import fi.vm.sade.hakurekisteri.integration.hakemus.HakemusServiceMock
import fi.vm.sade.hakurekisteri.integration.henkilo.MockOppijaNumeroRekisteri
import org.mockito.Mockito._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar

import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

class KoskiServiceSpec
    extends AnyFlatSpec
    with Matchers
    with MockitoSugar
    with DispatchSupport
    with LocalhostProperties {

  implicit val system = ActorSystem(s"test-system-${Platform.currentTime.toString}")
  implicit def executor: ExecutionContext = system.dispatcher
  val endPoint = mock[Endpoint]
  val client = new VirkailijaRestClient(
    ServiceConfig(serviceUrl = "http://localhost/koski"),
    aClient = Some(new CapturingAsyncHttpClient(endPoint))
  )
  val testRef = TestActorRef(new Actor {
    override def receive: Actor.Receive = { case q =>
      sender ! Seq()
    }
  })
  val koskiDataHandler: KoskiDataHandler = new KoskiDataHandler(testRef, testRef, testRef)
  val koskiService = new KoskiService(
    virkailijaRestClient = client,
    oppijaNumeroRekisteri = MockOppijaNumeroRekisteri,
    pageSize = 10,
    hakemusService = new HakemusServiceMock(),
    koskiDataHandler = koskiDataHandler,
    config = new MockConfig
  )

  override val jsonDir = "src/test/scala/fi/vm/sade/hakurekisteri/integration/koski/json/"

  /*it should "retry on occasional errors when updating henkilot for haku" in {
    val params =
      KoskiSuoritusTallennusParams(saveLukio = false, saveAmmatillinen = true, retryWaitMillis = 1000)
    val numeros = Range(1, 12345).map(n => s"1.2.3.$n")
    when(endPoint.request(forUrl("http://localhost/koski/api/sure/oids")))
      .thenReturn(
        (200, List(), "[]"),
        (200, List(), "[]"),
        (200, List(), "[]"),
        (200, List(), "[]"),
        (200, List(), "[]"),
        (200, List(), "[]")
      )
      .thenThrow(new RuntimeException("the first surprising failure!"))
      .thenReturn(
        (200, List(), "[]"),
        (200, List(), "[]"),
        (200, List(), "[]"),
        (200, List(), "[]"),
        (200, List(), "[]"),
        (200, List(), "[]")
      )
      .thenThrow(new RuntimeException("another one fails!"))
      .thenThrow(new RuntimeException("another one fails, 2 in a row!"))
      .thenThrow(new RuntimeException("another one fails, 3 in a row!"))
      .thenReturn((200, List(), "[]"))
    val future = koskiService.handleHenkiloUpdate(numeros, params, "just testing!")
    Await.result(future, 10.seconds)
  }*/

  /*  it should "return successful future for handleHenkiloUpdate" in {
    when(endPoint.request(forUrl("http://localhost/koski/api/sure/oids")))
      .thenReturn((200, List(), "[]"))
    val future = koskiService.handleHenkiloUpdate(
      Seq("1.2.3.4"),
      new KoskiSuoritusTallennusParams(saveLukio = true, saveAmmatillinen = true),
      "just testing!"
    )
    Await.result(future, 10.seconds)
  }*/

  //WIP
  it should "successfully create, poll and handle Koski massaluovutus call" in {
    when(endPoint.request(forUrl("http://localhost/koski/api/massaluovutus")))
      .thenReturn(
        (
          200,
          List(),
          "{\n  \"queryId\" : \"6d6fa78a-15af-4fef-a3cf-a7a555201ad2\",\n  \"requestedBy\" : \"1.2.246.562.24.123123123123\",\n  \"query\" : {\n    \"type\" : \"valintalaskenta\",\n    \"format\" : \"application/json\",\n    \"rajapäivä\" : \"2024-12-19\",\n    \"oppijaOids\" : [ \"1.2.246.562.24.37998958910\", \"1.2.246.562.24.62432463004\" ]\n  },\n  \"createdAt\" : \"2024-12-18T07:51:01.845187+02:00\",\n  \"resultsUrl\" : \"https://virkailija.testiopintopolku.fi/koski/api/massaluovutus/6d6fa78a-15af-4fef-a3cf-a7a555201ad2\",\n  \"status\" : \"pending\"\n}"
        )
      )

    when(
      endPoint.request(
        forUrl(
          "https://virkailija.testiopintopolku.fi/koski/api/massaluovutus/6d6fa78a-15af-4fef-a3cf-a7a555201ad2"
        )
      )
    )
      .thenReturn(
        (
          200,
          List(),
          "{\n  \"queryId\" : \"6d6fa78a-15af-4fef-a3cf-a7a555201ad2\",\n  \"requestedBy\" : \"1.2.246.562.24.123123123123\",\n  \"query\" : {\n    \"type\" : \"valintalaskenta\",\n    \"format\" : \"application/json\",\n    \"rajapäivä\" : \"2024-12-19\",\n    \"oppijaOids\" : [ \"1.2.246.562.24.37998958910\", \"1.2.246.562.24.62432463004\" ]\n  },\n  \"createdAt\" : \"2024-12-18T07:51:01.845187+02:00\",\n  \"startedAt\" : \"2024-12-18T07:51:02.845187+02:00\",\n  \"files\" : [ \"https://virkailija.testiopintopolku.fi/koski/api/massaluovutus/6d6fa78a-15af-4fef-a3cf-a7a555201ad2/1.2.246.562.24.37998958910.json\" ],\n  \"resultsUrl\" : \"https://virkailija.testiopintopolku.fi/koski/api/massaluovutus/6d6fa78a-15af-4fef-a3cf-a7a555201ad2\",\n  \"progress\" : {\n    \"percentage\" : 75,\n    \"estimatedCompletionTime\" : \"2024-12-19T22:36:03.845187\"\n  },\n  \"status\" : \"running\"\n}"
        ),
        (200, List(), "")
      )

    val future = koskiService.handleKoskiRefreshForOppijaOids(
      Set("1.2.3.4"),
      new KoskiSuoritusTallennusParams(saveLukio = true, saveAmmatillinen = true)
    )
    Await.result(future, 10.seconds)
  }

}
