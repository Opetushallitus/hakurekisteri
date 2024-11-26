package fi.vm.sade.hakurekisteri

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.testkit.TestActorRef
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.test.tools.FutureWaiting
import org.scalatest.matchers.should
import org.scalatra.test.scalatest.ScalatraFunSuite

import scala.concurrent.Await
import scala.concurrent.duration._
import org.scalatest.Assertions
class ResourceActorSpec extends ScalatraFunSuite with FutureWaiting with Assertions {
  implicit val timeout: Timeout = 5.seconds

  test("ResourceActor should save resource when receiving it") {
    implicit val system = ActorSystem("test-system")
    val resourceActor = TestActorRef[TestActor]
    val resource = new TestResource("foo")
    resourceActor ! resource
    resourceActor.underlyingActor.store.values should contain(resource)

    Await.result(system.terminate(), 15.seconds)
  }

  test("ResourceActor fails when receiving an unknown message") {
    implicit val system = ActorSystem("test-system")
    val resourceActor = TestActorRef[TestActor]
    val future = resourceActor ? "foo"
    intercept[Exception] {
      Await.result(future.mapTo[String], 1.seconds)
    }
    Await.result(system.terminate(), 15.seconds)
  }

  override def header = ???
}
