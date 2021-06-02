package fi.vm.sade.hakurekisteri.koodisto

import akka.actor.ActorSystem
import akka.testkit.TestActorRef
import fi.vm.sade.hakurekisteri.{MockCacheFactory, MockConfig}
import fi.vm.sade.hakurekisteri.integration.VirkailijaRestClient
import fi.vm.sade.hakurekisteri.integration.koodisto.{
  GetRinnasteinenKoodiArvoQuery,
  Koodi,
  Koodisto,
  KoodistoActor
}
import org.junit.runner.RunWith
import org.scalatest.Matchers
import org.scalatest.junit.JUnitRunner
import org.scalatra.test.scalatest.ScalatraFunSuite
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class KoodistoActorSpec extends ScalatraFunSuite with Matchers with MockitoSugar {
  private val koodisto = "koodisto"
  private val koodiArvo = "arvo"
  private val koodiUri = "uri"
  private val rinnasteinenKoodisto = "rinnasteinenKoodisto"
  private val rinnasteinenArvo = "rinnasteinenArvo"
  private val rinnasteinenUri = "rinnasteinenUri"
  private val responseCode = 200
  private val maxRetries = 1

  implicit val system: ActorSystem = ActorSystem()
  val cacheFactory = MockCacheFactory.get

  val mockRestClient = mock[VirkailijaRestClient]
  val mockKoodi = Koodi(koodiArvo, 1, koodiUri, Koodisto(koodisto), null)
  val mockRinnasteinen =
    Koodi(rinnasteinenArvo, 1, rinnasteinenUri, Koodisto(rinnasteinenKoodisto), null)
  val koodistoActor = TestActorRef(
    new KoodistoActor(mockRestClient, new MockConfig(), cacheFactory)
  ).underlyingActor

  when(
    mockRestClient.readObject[Seq[Koodi]](
      "koodisto-service.koodisByKoodistoAndArvo",
      koodisto,
      koodiArvo
    )(responseCode, maxRetries)
  ).thenReturn(
    Future.successful(Seq(mockKoodi))
  )
  when(
    mockRestClient.readObject[Seq[Koodi]]("koodisto-service.koodisByKoodisto", koodiUri)(
      responseCode,
      maxRetries
    )
  ).thenReturn(
    Future.successful(Seq(mockKoodi))
  )

  when(
    mockRestClient.readObject[Seq[Koodi]]("koodisto-service.relaatio", "rinnasteinen", koodiUri)(
      responseCode,
      maxRetries
    )
  ).thenReturn(
    Future.successful(Seq(mockRinnasteinen))
  )

  test("should fetch rinnasteinen koodiarvo from koodisto for given koodiarvo") {
    Await.result(
      koodistoActor.getRinnasteinenKoodiArvo(
        GetRinnasteinenKoodiArvoQuery(koodisto, koodiArvo, rinnasteinenKoodisto)
      ),
      Duration.Inf
    ) should be(mockRinnasteinen.koodiArvo)
  }

  override def stop(): Unit = {
    Await.result(system.terminate(), 15.seconds)
    super.stop()
  }
}
