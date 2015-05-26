package fi.vm.sade.hakurekisteri

import akka.actor.ActorSystem
import akka.testkit.TestActorRef
import org.scalatest.Matchers
import org.scalatra.test.scalatest.ScalatraFunSuite

import scala.concurrent.duration._

class ResourceActorSpec extends ScalatraFunSuite with Matchers {

  implicit val system = ActorSystem("test-system")
  implicit val ec = system.dispatcher

  val resourceActor = TestActorRef[TestActor]

  test("ResourceActor should save resource when receiving it") {
    val resource = new TestResource("foo")
    resourceActor ! resource
    resourceActor.underlyingActor.store.values should contain (resource)
  }

  test("ResourceActor should not fail when receiving an unknown message") {
    resourceActor ! "foo"
  }

  override def stop(): Unit = {
    system.shutdown()
    system.awaitTermination(15.seconds)
    super.stop()
  }

}




