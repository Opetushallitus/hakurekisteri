package fi.vm.sade.hakurekisteri

import java.util.UUID

import akka.actor.{ActorSystem, Props}
import akka.pattern.ask
import akka.testkit.TestActorRef
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.arvosana.{Arvosana, ArvosanaActor}
import fi.vm.sade.hakurekisteri.rest.support.Resource
import fi.vm.sade.hakurekisteri.storage.repository.Repository
import fi.vm.sade.hakurekisteri.storage.{DeleteResource, Identified}
import fi.vm.sade.hakurekisteri.test.tools.FutureWaiting
import org.scalatest.Matchers
import org.scalatra.test.scalatest.ScalatraFunSuite

import scala.concurrent.Await
import scala.concurrent.duration._


class ResourceActorSpec extends ScalatraFunSuite with Matchers with FutureWaiting {
  implicit val system = ActorSystem("test-system")
  implicit val timeout: Timeout = 5.seconds

  test("ResourceActor should save resource when receiving it") {
    val resourceActor = TestActorRef[TestActor]
    val resource = new TestResource("foo")
    resourceActor ! resource
    resourceActor.underlyingActor.store.values should contain (resource)
  }

  test("ResourceActor should not fail when receiving an unknown message") {
    val resourceActor = TestActorRef[TestActor]
    resourceActor ! "foo"
  }
  
  test("ResourceActor should not crash when journal operation fails") {
    val arvosanaActor = TestActorRef(Props(new ArvosanaActor() with CrashingRepository[Arvosana, UUID]))
    expectFailure[Exception](arvosanaActor ? DeleteResource(UUID.randomUUID(), "testUser"))
  }

  override def stop(): Unit = {
    Await.result(system.terminate(), 15.seconds)
    super.stop()
  }

  trait CrashingRepository[T <: Resource[I, T], I] extends Repository[T, I] {
    override def save(t: T): T with Identified[I] = ???
    override def count: Int = ???
    override def insert(t: T): T with Identified[I] = ???
    override def get(id: I): Option[T with Identified[I]] = ???
    override def cursor(t: T): Option[(Long, String)] = ???
    override def delete(id: I, source: String): Unit = throw new Exception("failing delete exception")
    override def listAll(): Seq[T with Identified[I]] = ???
  }

}




