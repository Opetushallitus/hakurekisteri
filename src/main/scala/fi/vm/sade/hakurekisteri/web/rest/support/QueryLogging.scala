package fi.vm.sade.hakurekisteri.web.rest.support

import scala.compat.Platform
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Try, Failure, Success}
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack

trait QueryLogging { this: HakuJaValintarekisteriStack =>

  private def result(t: Try[_]): String = t match {
    case Success(_) => "success"
    case Failure(e) => s"failure: $e"
  }

  def logQuery(q: Any, t0: Long, f: Future[_])(implicit ec: ExecutionContext): Unit = {
    f.onComplete(t => {
      val requestLength = Platform.currentTime - t0
      val message = s"query $q took $requestLength ms, result ${result(t)}"
      requestLength match {
        case reallySlow if requestLength > 1000  => logger.warning(message)
        case slow if requestLength > 100 => logger.info(message)
        case normal => logger.debug(message)
      }
    })
  }

}
