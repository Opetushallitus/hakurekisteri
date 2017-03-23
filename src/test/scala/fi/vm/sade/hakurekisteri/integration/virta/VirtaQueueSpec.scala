package fi.vm.sade.hakurekisteri.integration.virta

import akka.actor.{Actor, ActorSystem, Props}
import akka.pattern.ask
import akka.testkit.TestActorRef
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.dates.{Ajanjakso, InFuture}
import fi.vm.sade.hakurekisteri.integration.hakemus.HakemusServiceMock
import fi.vm.sade.hakurekisteri.integration.haku.{GetHaku, Haku, Kieliversiot}
import fi.vm.sade.hakurekisteri.integration.henkilo.MockOppijaNumeroRekisteri
import fi.vm.sade.hakurekisteri.test.tools.FutureWaiting
import org.joda.time.DateTime
import org.scalatest.{Matchers, WordSpec}

import scala.concurrent.Await

class VirtaQueueSpec extends WordSpec with Matchers with FutureWaiting {
  implicit val system = ActorSystem("test-virta-queue")

  "VirtaQueue" when {
    val virtaWaiter = new Waiter()
    val virtaHandler: PartialFunction[Any, Any] = {
      case q: VirtaQuery =>
        virtaWaiter { q.oppijanumero should be("foo") }
        virtaWaiter.dismiss()
        QueryProsessed(q)
    }
    val hakuHandler: PartialFunction[Any, Any] = {
      case q: GetHaku =>
        Haku(Kieliversiot(Some("haku"), None, None), "1.2", Ajanjakso(new DateTime(), InFuture), "kausi_s#1", 2014,
          Some("kausi_k#1"), Some(2015), true, None, None)
    }

    val virtaActor = TestActorRef[MockActor](Props(new MockActor(virtaHandler)))
    val hakemusActor = TestActorRef[MockActor](Props(new MockActor()))
    val hakuActor = TestActorRef[MockActor](Props(new MockActor(hakuHandler)))
    val hakemusService = new HakemusServiceMock

    "receiving query" should {
      val virtaQueue: TestActorRef[VirtaQueue] = TestActorRef[VirtaQueue](Props(new VirtaQueue(virtaActor, hakemusService, oppijaNumeroRekisteri = MockOppijaNumeroRekisteri, hakuActor)))
      val q = VirtaQuery("foo", Some("bar"))
      virtaQueue ! q

      "put it in queue" in {
        virtaQueue.underlyingActor.virtaQueue should contain(q)
      }
    }

    "consuming all" should {
      val virtaQueue: TestActorRef[VirtaQueue] = TestActorRef[VirtaQueue](Props(new VirtaQueue(virtaActor, hakemusService, oppijaNumeroRekisteri = MockOppijaNumeroRekisteri, hakuActor)))
      virtaQueue ! VirtaQuery("foo", Some("bar"))
      virtaQueue ! VirtaQuery("foo", Some("bar2"))
      virtaQueue ! StartVirtaProcessing

      "start consuming queries in the queue" in {
        import org.scalatest.time.SpanSugar._
        virtaWaiter.await(timeout(10.seconds), dismissals(2))
        virtaQueue.underlyingActor.virtaQueue.size should be(0)
      }
    }

    "receiving the same query multiple times" should {
      val virtaQueue: TestActorRef[VirtaQueue] = TestActorRef[VirtaQueue](Props(new VirtaQueue(virtaActor, hakemusService, oppijaNumeroRekisteri = MockOppijaNumeroRekisteri, hakuActor)))
      val q1 = VirtaQuery("foo", Some("bar"))
      val q2 = VirtaQuery("foo", Some("bar"))
      virtaQueue ! q1
      virtaQueue ! q2

      "put it in the queue only once" in {
        virtaQueue.underlyingActor.virtaQueue.size should be(1)
      }
    }

    "receiving 1 000 000 queries" should {
      import scala.concurrent.duration._
      implicit val timeout: Timeout = 30.seconds

      val virtaQueue: TestActorRef[VirtaQueue] = TestActorRef[VirtaQueue](Props(new VirtaQueue(virtaActor, hakemusService, oppijaNumeroRekisteri = MockOppijaNumeroRekisteri, hakuActor)))
      (0 until 1000000).foreach(i => virtaQueue ! VirtaQuery(s"foo$i", None))

      val healthcheck = Await.result((virtaQueue ? VirtaHealth).mapTo[VirtaStatus], 30.seconds)
      "be fast enough to respond to healthcheck in 30 seconds" in {
        healthcheck.queueLength should be (1000000)
      }
    }
  }
}

class MockActor(handler: PartialFunction[Any, Any] = { case a: Any => a }) extends Actor {
  override def receive: Receive = {
    case a: Any =>
      val q = handler(a)
      sender ! q
  }
}
