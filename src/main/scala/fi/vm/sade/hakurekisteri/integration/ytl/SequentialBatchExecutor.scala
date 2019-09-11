package fi.vm.sade.hakurekisteri.integration.ytl

import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}

object SequentialBatchExecutor {
  private val logger = LoggerFactory.getLogger(getClass)
  private type Batch[A] = Seq[A]

  def runInBatches[A](allItems: Iterator[A], batchSize: Int,
                      batchExecutor: BatchExecutor[A] = new RealBatchExecutor[A])
                     (itemFunction: A => Future[Unit])
                     (implicit ec: ExecutionContext): Future[Unit] = {
    val batches: Seq[Batch[A]] = allItems.grouped(batchSize).toList
    performBatchesSequentially(batches, batchExecutor)(itemFunction)
  }

  private def performBatchesSequentially[A](batches: Seq[Batch[A]], batchExecutor: BatchExecutor[A])
                                   (itemFunction: A => Future[Unit])(implicit ec: ExecutionContext): Future[Unit] = {
    batches.headOption match {
      case Some(nextBatch) =>
        val fut = batchExecutor.executeBatch(nextBatch, itemFunction)
        fut.flatMap { _ =>
          // successful, let's move on to the next!
          performBatchesSequentially(batches.tail, batchExecutor)(itemFunction)
        }
      case None =>
        // nothing left to process
        Future.successful(())
    }
  }

  class RealBatchExecutor[A] extends BatchExecutor[A] {
    override def executeBatch(batch: Batch[A], itemFunction: A => Future[Unit])(implicit ec: ExecutionContext): Future[Unit] = {
      val futuresForAllItems: Seq[Future[Unit]] = batch.map { item => itemFunction(item)}
      logger.debug(s"Executing batch (size=${batch.length}) $batch")
      Future.sequence(futuresForAllItems).map[Unit](_ => ())
    }
  }

  trait BatchExecutor[A] {
    def executeBatch(batch: Batch[A], itemFunction: A => Future[Unit])(implicit ec: ExecutionContext): Future[Unit]
  }
}
