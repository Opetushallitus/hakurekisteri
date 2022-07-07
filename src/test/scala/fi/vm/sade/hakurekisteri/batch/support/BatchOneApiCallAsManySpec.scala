package fi.vm.sade.hakurekisteri.batch.support

import fi.vm.sade.hakurekisteri.integration.ExecutorUtil
import org.scalatra.test.scalatest.ScalatraFunSuite

import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.immutable
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContextExecutorService, Future}
import scala.util.{Failure, Success}

class BatchOneApiCallAsManySpec extends ScalatraFunSuite {
  implicit val batchEc: ExecutionContextExecutorService =
    ExecutorUtil.createExecutor(1, "batch-test-pool")
  val testOids: List[String] = Range.inclusive(0, 10).map(_.toString).toList

  test("batcher works on happy path") {
    val oidsToCall = (oids: Set[String]) => {
      Future.successful(oids.toList)
    }

    val b = new BatchOneApiCallAsMany[String](1, "KoutaHakukohdeBatcher", oidsToCall, 5)

    def sendToBatch(oid: String) = {
      b.batch(oid, o => oid.equals(o))
    }

    val batches = Future.sequence(testOids.map(sendToBatch))

    val result = Await.result(batches, 5.second)
    result should be(testOids)
  }

  test("batcher works when API calls randomly fail") {
    val firstFails = new AtomicBoolean(true)

    val oidsToCall = (oids: Set[String]) => {
      if (firstFails.getAndSet(false)) {
        Future.failed(new RuntimeException("First batch fails"))
      } else {
        Future.successful(oids.toList)
      }
    }

    val b = new BatchOneApiCallAsMany[String](1, "KoutaHakukohdeBatcher", oidsToCall, 5)

    def sendToBatch(oid: String) = {
      b.batch(oid, o => oid.equals(o))
    }

    val batches = testOids.map(sendToBatch)
    batches.foreach(b => Await.ready(b, 5.second))

    val successful = batches.filter(_.value match {
      case Some(Success(_)) => true
      case _                => false
    })
    val failed = batches.filter(_.value match {
      case Some(Failure(_)) => true
      case _                => false
    })

    successful should not be empty
    failed should not be empty
  }
}
