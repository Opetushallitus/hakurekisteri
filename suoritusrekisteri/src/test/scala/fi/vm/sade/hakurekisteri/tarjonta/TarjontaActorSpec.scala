package fi.vm.sade.hakurekisteri.tarjonta

import akka.actor.ActorSystem
import akka.testkit.TestActorRef
import fi.vm.sade.hakurekisteri.MockConfig
import fi.vm.sade.hakurekisteri.integration.haku.{RestHaku, RestHakuAika}
import fi.vm.sade.hakurekisteri.integration.tarjonta.{
  MockTarjontaActor,
  TarjontaRestHaku,
  TarjontaRestHakuAika
}
import org.joda.time.LocalDate
import org.scalatra.test.scalatest.ScalatraFunSuite

import scala.concurrent.Await
import scala.concurrent.duration._

class TarjontaActorSpec extends ScalatraFunSuite {

  implicit val system: ActorSystem = ActorSystem()

  val tarjontaUnderlyingActor = TestActorRef(
    new MockTarjontaActor(new MockConfig())
  ).underlyingActor
  val jatkotutkintohaunTarkenne = "haunkohdejoukontarkenne_3#1"
  val mockHaku = TarjontaRestHaku(
    oid = Some("1.2.3.4"),
    hakuaikas = List(TarjontaRestHakuAika(1, Some(new LocalDate().plusMonths(1).toDate.getTime))),
    nimi = Map("kieli_fi" -> "haku 1", "kieli_sv" -> "haku 1", "kieli_en" -> "haku 1"),
    hakukausiUri = "kausi_k#1",
    hakutapaUri = "hakutapa_01#1",
    hakukausiVuosi = new LocalDate().getYear,
    koulutuksenAlkamiskausiUri = Some("kausi_s#1"),
    koulutuksenAlkamisVuosi = Some(new LocalDate().getYear),
    kohdejoukkoUri = Some("haunkohdejoukko_12#1"),
    kohdejoukonTarkenne = None,
    tila = "LUONNOS",
    hakutyyppiUri = "hakutyyppi_01#1",
    None
  )

  test("luonnos is not included") {
    tarjontaUnderlyingActor.includeHaku(mockHaku.toRestHaku) should be(false)
  }

  test("julkaistu is included") {
    tarjontaUnderlyingActor.includeHaku(mockHaku.copy(tila = "JULKAISTU").toRestHaku) should be(
      true
    )
  }

  test("valmis is not included") {
    tarjontaUnderlyingActor.includeHaku(mockHaku.copy(tila = "VALMIS").toRestHaku) should be(false)
  }

  test("jatkotutkintohaku is not included") {
    tarjontaUnderlyingActor.includeHaku(
      mockHaku.copy(kohdejoukonTarkenne = Some(jatkotutkintohaunTarkenne)).toRestHaku
    ) should be(false)
  }

  test("valmis jatkotutkintohaku is included") {
    tarjontaUnderlyingActor.includeHaku(
      mockHaku
        .copy(tila = "VALMIS", kohdejoukonTarkenne = Some(jatkotutkintohaunTarkenne))
        .toRestHaku
    ) should be(true)
  }

  override def stop(): Unit = {
    Await.result(system.terminate(), 15.seconds)
    super.stop()
  }

  override def header = ???
}
