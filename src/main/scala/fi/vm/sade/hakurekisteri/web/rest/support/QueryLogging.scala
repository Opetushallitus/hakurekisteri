package fi.vm.sade.hakurekisteri.web.rest.support

import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack

import scala.compat.Platform
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

trait QueryLogging { this: HakuJaValintarekisteriStack =>

  private def result(t: Try[_]): String = t match {
    case Success(_) => "success"
    case Failure(e) => s"failure: $e"
  }

  def logQuery(q: Any, t0: Long, f: Future[_])(implicit ec: ExecutionContext): Unit = {
    f.onComplete(t => {
      val requestDurationMillis = Platform.currentTime - t0
      val queryStr = if (q.toString.length > 500) {
        s"${q.toString.take(500)}...(truncated from ${q.toString.length} chars)"
      } else {
        q.toString
      }

      val message = s"Query $queryStr took $requestDurationMillis ms, result ${result(t)}"
      requestDurationMillis match {
        case reallySlow if requestDurationMillis > 10000 => logger.warning(message)
        case slow if requestDurationMillis > 200         => logger.info(message)
        case normal                                      => logger.debug(message)
      }
    })
  }

}
