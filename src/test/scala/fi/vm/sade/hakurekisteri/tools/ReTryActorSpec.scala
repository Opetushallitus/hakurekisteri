// inspired by com.codetinkerhack

package fi.vm.sade.hakurekisteri.tools

import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props, actorRef2Scala}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike}

import scala.collection.immutable.IndexedSeq
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Await, blocking}
import scala.util.{Success}

object MockWorker {
  def props(): Props = Props(new MockWorker())
  def taskProps(): Props = Props(new TaskRunner())
}

case class Task(i: FiniteDuration, aRef: ActorRef)

case class Work(delays: IndexedSeq[FiniteDuration])

class TaskRunner extends Actor with ActorLogging {

  def receive = {
    case Task(i, aRef) =>
      blocking {
        Thread.sleep(i.toMillis)
        aRef ! Success("42") // the ultimate answer...
        // log.info("Task executed for: " + i)
      }
  }
}

class MockWorker extends Actor  {

  var i = 0

  def receive = {

    case Work(delays) =>
      if(delays.size > i) {
        context.actorOf(MockWorker.taskProps()) ! Task(delays(i), sender)
        i+=1
      }

  }

}

class ReTryActorSpec extends TestKit(ActorSystem("RetryTestSpec"))
  with FlatSpecLike
  with BeforeAndAfterAll
  with ImplicitSender {

  override def afterAll(): Unit = {
    Await.result(system.terminate(), 15.seconds)
  }

  behavior of "Mock Worker"

  it should "Return success response" in {

    val client = TestProbe()
    val mockWorker = system.actorOf(MockWorker.props(), "case1-genericMockWorkerTest")

    val expectedResult = Success("42") // the ultimate answer...
    client.send(mockWorker, Work(IndexedSeq(1.second)))
    client.expectMsg(2.second, expectedResult)

  }


  behavior of "ReTryActor used as proxy for Mock Worker"

  it should "Reply success" in {
    val client = TestProbe()
    val mockWorker = system.actorOf(MockWorker.props(), "case2-withRetryProxy")

    val mockWorkerWrappedInProxy = system.actorOf(ReTryActor.props(tries = 1, retryTimeOut = 1000.millis, retryInterval = 100.millis, mockWorker))
    val success = Success("42")

    client.send(mockWorkerWrappedInProxy, Work(IndexedSeq(200.millis)))
    client.expectMsg(2.seconds, success)
  }

  it should "Fail when 1 try and timeout - retries exceeded" in {
    val client = TestProbe()
    val mockWorker = system.actorOf(MockWorker.props(), "case3-withRetryProxy")

    val mockWorkerWrappedInProxy = system.actorOf(ReTryActor.props(tries = 1, retryTimeOut = 1000.millis, retryInterval = 100.millis, mockWorker))


    client.send(mockWorkerWrappedInProxy, Work(IndexedSeq(1100.millis)))
    client.expectMsgPF(2.seconds) { case akka.actor.Status.Failure(e: Exception) if e.getMessage() == "Retries exceeded" => () }
  }

  it should "Fail when retries exceeded, 4 tries and all time out" in {
    val client = TestProbe()
    val mockWorker = system.actorOf(MockWorker.props(), "case4-withRetryProxy")

    val mockWorkerWrappedInProxy = system.actorOf(ReTryActor.props(tries = 4, retryTimeOut = 1000.millis, retryInterval = 100.millis, mockWorker))

    client.send(mockWorkerWrappedInProxy, Work(IndexedSeq(1100.millis, 1100.millis, 1100.millis, 1100.millis)))
    client.expectMsgPF(5.seconds) { case akka.actor.Status.Failure(e: Exception) => () }
  }

  it should "Succeed with 4 tries and last is successful" in {
    val client = TestProbe()
    val mockWorker = system.actorOf(MockWorker.props(), "case5-withRetryProxy")

    val mockWorkerWrappedInProxy = system.actorOf(ReTryActor.props(tries = 4, retryTimeOut = 1000.millis, retryInterval = 100.millis, mockWorker))
    val success = Success("42")

    client.send(mockWorkerWrappedInProxy, Work(IndexedSeq(1101.millis, 1102.millis, 1103.millis, 104.millis)))
    client.expectMsg(5.seconds, success)
  }

  it should "Succeed when 4 tries and first is successful" in {
    val client = TestProbe()
    val mockWorker = system.actorOf(MockWorker.props(), "case6-withRetryProxy")

    val mockWorkerWrappedInProxy = system.actorOf(ReTryActor.props(tries = 4, retryTimeOut = 1000.millis, retryInterval = 100.millis, mockWorker))
    val success = Success("42")

    client.send(mockWorkerWrappedInProxy, Work(IndexedSeq(101.millis, 1102.millis, 1103.millis, 104.millis)))
    client.expectMsg(5.seconds, success)
  }
}