package fi.vm.sade.hakurekisteri.integration.henkilo

import akka.actor.ActorSystem
import fi.vm.sade.hakurekisteri.MockDevConfig
import fi.vm.sade.hakurekisteri.acceptance.tools.HakeneetSupport
import fi.vm.sade.hakurekisteri.integration.{
  CapturingAsyncHttpClient,
  DispatchSupport,
  Endpoint,
  LocalhostProperties,
  ServiceConfig,
  VirkailijaRestClient
}
import fi.vm.sade.utils.slf4j.Logging
import org.mockito.Mockito.when
import org.scalatest.concurrent.Waiters
import org.scalatestplus.mockito.MockitoSugar
import org.scalatra.test.scalatest.ScalatraFunSuite

import scala.concurrent.Await
import scala.concurrent.duration._

class OppijaNumeroRekisteriTest
    extends ScalatraFunSuite
    with HakeneetSupport
    with MockitoSugar
    with DispatchSupport
    with Waiters
    with LocalhostProperties
    with Logging {
  private val endPoint = mock[Endpoint]
  private val oppijaNumeroRekisteriClient = new VirkailijaRestClient(
    ServiceConfig(serviceUrl = "http://localhost/oppijanumerorekisteri-service"),
    aClient = Some(new CapturingAsyncHttpClient(endPoint))
  )
  private val oppijaNumeroRekisteriService =
    new OppijaNumeroRekisteri(oppijaNumeroRekisteriClient, ActorSystem("test"), new MockDevConfig())

  test("should return all aliases and correct master-slave information") {
    when(endPoint.request(forPattern(".*/duplicateHenkilos")))
      .thenReturn((200, List(), getJson("oppijaNumeroRekisteri")))

    val aliakset: LinkedHenkiloOids = Await.result(
      oppijaNumeroRekisteriService.fetchLinkedHenkiloOidsMap(Set("111")),
      15.seconds
    )

    aliakset.oidToLinkedOids.size should be(1)
    aliakset.oidToLinkedOids.get("111").get.size should be(2)
    aliakset.oidToMasterOid.size should be(1)
    aliakset.oidToMasterOid.get("111").get should be("321")
  }
}
