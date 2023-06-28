package fi.vm.sade.hakurekisteri.integration.kouta.KoutaInternalActorSpec

import akka.actor.Props
import akka.pattern.ask
import fi.vm.sade.hakurekisteri.MockConfig
import fi.vm.sade.hakurekisteri.acceptance.tools.HakeneetSupport
import fi.vm.sade.hakurekisteri.integration.hakukohde.HakukohteenKoulutuksetQuery
import fi.vm.sade.hakurekisteri.integration.koodisto.KoodistoActorRef
import fi.vm.sade.hakurekisteri.integration.kouta.KoutaInternalActor
import fi.vm.sade.hakurekisteri.integration.tarjonta.{
  HakukohteenKoulutukset,
  Hakukohteenkoulutus,
  TarjontaKoodi
}
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
import org.scalatest.mockito.MockitoSugar
import org.scalatra.test.scalatest.ScalatraFunSuite

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

class KoutaInternalActorSpec
    extends ScalatraFunSuite
    with HakeneetSupport
    with MockitoSugar
    with DispatchSupport
    with Waiters
    with LocalhostProperties
    with Logging {
  private val endPoint = mock[Endpoint]
  private val koutaInternalClient = new VirkailijaRestClient(
    ServiceConfig(serviceUrl = "http://localhost/oppijanumerorekisteri-service"),
    aClient = Some(new CapturingAsyncHttpClient(endPoint))
  )
  private val koodistoMock = KoodistoActorRef(
    system.actorOf(
      Props(
        new MockedKoodistoActor()
      )
    )
  )
  private val koutaInternalActor = system.actorOf(
    Props(
      new KoutaInternalActor(koodistoMock, koutaInternalClient, new MockConfig())
    )
  )

  test("koutaInternalActor handles koulutukseen johtamaton koulutus") {
    when(endPoint.request(forPattern(".*/kouta-internal/hakukohde/findbyoids")))
      .thenReturn((200, List(), getJson("koutaHakukohde")))
    when(
      endPoint.request(
        forPattern(".*/kouta-internal/toteutus/1.2.246.562.17.00000000000000011399")
      )
    )
      .thenReturn((200, List(), getJson("koutaToteutus")))
    when(
      endPoint.request(
        forPattern(".*/kouta-internal/koulutus/1.2.246.562.13.00000000000000003172")
      )
    )
      .thenReturn((200, List(), getJson("koutaKoulutus")))
    val hakukohdeOid = "1.2.246.562.20.00000000000000027357"
    val hakukohteenKoulutukset: HakukohteenKoulutukset = Await.result(
      (koutaInternalActor ? HakukohteenKoulutuksetQuery(hakukohdeOid))
        .mapTo[HakukohteenKoulutukset],
      5.seconds
    )
    hakukohteenKoulutukset should be(
      HakukohteenKoulutukset(
        hakukohdeOid,
        None,
        Seq(
          Hakukohteenkoulutus(
            "1.2.246.562.13.00000000000000003172",
            None,
            None,
            Some(TarjontaKoodi(Some("K"))),
            Some(2023),
            None,
            None,
            Some(false)
          )
        )
      )
    )
  }
}
