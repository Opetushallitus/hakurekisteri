package fi.vm.sade.hakurekisteri

import org.scalatest.{Matchers, WordSpec}
import akka.actor.ActorSystem
import akka.testkit.TestActorRef


class ResourceActorSpec extends WordSpec with Matchers {
  implicit val system = ActorSystem("test-system")


  implicit val ec = system.dispatcher

  "A resource Actor" when {

    val resourceActor = TestActorRef[TestActor]
    "receiving resource" should {
      val resource = new TestResource("foo")
      resourceActor ! resource
      "save it"  in {
        resourceActor.underlyingActor.store.values should contain (resource)
      }

    }
    "receiving not a resource" should {
      resourceActor ! "foo"
      "not fail" in {

      }
    }

  }

}




