package fi.vm.sade.hakurekisteri.integration.virta

import akka.actor.{Actor, Props, ActorSystem}
import akka.testkit.TestActorRef
import fi.vm.sade.hakurekisteri.test.tools.FutureWaiting
import org.scalatest.{WordSpec, Matchers}

class VirtaQueueSpec extends WordSpec with Matchers with FutureWaiting {
  implicit val system = ActorSystem("test-virta-queue")

  "VirtaQueue" when {
    val virtaActor = TestActorRef[MockActor]
    val hakemusActor = TestActorRef[MockActor]

    "receiving query" should {
      val virtaQueue: TestActorRef[VirtaQueue] = TestActorRef[VirtaQueue](Props(classOf[VirtaQueue], virtaActor, hakemusActor))
      val q = VirtaQuery("foo", Some("bar"))
      virtaQueue ! VirtaQueuedQuery(q)

      "put it in queue" in {
        virtaQueue.underlyingActor.virtaQueue should contain(q)
      }
    }

    "consuming all" should {
      val virtaQueue: TestActorRef[VirtaQueue] = TestActorRef[VirtaQueue](Props(classOf[VirtaQueue], virtaActor, hakemusActor))
      val q = VirtaQuery("foo", Some("bar"))
      virtaQueue ! VirtaQueuedQuery(q)
      virtaQueue ! ProcessAll

      "consume all queries in the queue" in {
        virtaQueue.underlyingActor.virtaQueue.length should be(0)
      }
    }

    "receiving the same query multiple times" should {
      val virtaQueue: TestActorRef[VirtaQueue] = TestActorRef[VirtaQueue](Props(classOf[VirtaQueue], virtaActor, hakemusActor))
      val q1 = VirtaQuery("foo", Some("bar"))
      val q2 = VirtaQuery("foo", Some("bar"))
      virtaQueue ! VirtaQueuedQuery(q1)
      virtaQueue ! VirtaQueuedQuery(q2)

      "put it in the queue only once" in {
        virtaQueue.underlyingActor.virtaQueue.length should be(1)
      }
    }
  }

  class MockActor extends Actor {
    override def receive: Receive = {
      case a: Any => sender ! a
    }
  }

}
