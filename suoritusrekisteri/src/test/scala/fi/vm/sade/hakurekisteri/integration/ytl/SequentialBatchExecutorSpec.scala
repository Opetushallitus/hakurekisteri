package fi.vm.sade.hakurekisteri.integration.ytl

import org.mockito.Mockito
import org.mockito.Mockito.spy
import org.scalatest.flatspec.AsyncFlatSpec

import scala.concurrent.{Future}

class SequentialBatchExecutorSpec extends AsyncFlatSpec {

  behavior of "SequentialBatchExecutor"

  it should "execute in 3 batches when there are 11 items and batch size 4" in {
    val items: Seq[Int] = 1 to 11

    val realBatchExecutor: SequentialBatchExecutor.RealBatchExecutor[Int] =
      new SequentialBatchExecutor.RealBatchExecutor[Int]
    val spiedBatchExecutor = spy[SequentialBatchExecutor.RealBatchExecutor[Int]](realBatchExecutor)

    val result: Future[Unit] =
      SequentialBatchExecutor.runInBatches(items.iterator, 4, spiedBatchExecutor) { _ =>
        Future {
          Thread.sleep(10)
        }
      }
    result map { _ =>
      {
        Mockito
          .verify(spiedBatchExecutor, Mockito.times(3))
          .executeBatch(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())(
            org.mockito.ArgumentMatchers.any()
          )
        assert(true)
      }
    }
  }
}
