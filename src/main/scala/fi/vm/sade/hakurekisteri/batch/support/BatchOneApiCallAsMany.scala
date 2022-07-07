package fi.vm.sade.hakurekisteri.batch.support

import fi.vm.sade.hakurekisteri.integration.ExecutorUtil
import fi.vm.sade.utils.slf4j.Logging

import java.util.concurrent.ConcurrentLinkedDeque
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

private case class Work[T](promise: Promise[T], oid: String, tester: T => Boolean, executor: ExecutionContext) {
  private val createdAt = System.currentTimeMillis()

  def took: Long = System.currentTimeMillis() - createdAt
}

class BatchOneApiCallAsMany[T](poolSize: Int,
                               poolName: String,
                               oidsToApi: Set[String] => Future[List[T]],
                               oidsAtMostPerSingleApiCall: Int = 500) extends Logging {

  private val TOOK_TOO_LONG_MS: Long = 5000;
  private val stack = new ConcurrentLinkedDeque[Work[T]]()
  private val pool = ExecutorUtil.createExecutor(poolSize, poolName)

  private def makeApiCall: Runnable = () => {
    val works = Range.inclusive(1, oidsAtMostPerSingleApiCall).flatMap(_ => {
      Option(stack.poll())
    })
    val oids = works.map(w => w.oid).toSet
    if(oids.isEmpty) {
      logger.debug(s"All work already done in $poolName")
    } else {
      val tryResults = Try(oidsToApi(oids))
      works.foreach(w => {
        try {
          tryResults match {
            case Success(results) =>
              val fut = results.flatMap(o => o.find(w.tester) match {
                case Some(result) => Future.successful(result)
                case None => Future.failed(new RuntimeException(s"Couldn't find data for oid ${w.oid} in $poolName!"))
              })(w.executor)
              val took = w.took
              if(took > TOOK_TOO_LONG_MS) {
                logger.warn(s"Fetching ${w.oid} took ${took}ms in $poolName!")
              }
              w.promise.completeWith(fut)
            case Failure(exception) =>
              w.promise.completeWith(Future.failed(exception))
          }
        } catch {
          case e: Throwable =>
            w.promise.completeWith(Future.failed(e))
        }
      })
    }
  }

  def batch(oid: String, tester: T => Boolean)(implicit executor: ExecutionContext): Future[T] = {
    val p = Work[T](Promise[T](), oid, tester, executor)
    stack.add(p)
    pool.submit(makeApiCall)
    p.promise.future
  }

}
