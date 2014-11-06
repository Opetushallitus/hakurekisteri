package fi.vm.sade.hakurekisteri.rest.support

import fi.vm.sade.hakurekisteri.HakuJaValintarekisteriStack

import scala.compat.Platform
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Try, Failure, Success}

trait QueryLogging { this: HakuJaValintarekisteriStack =>

  private def result(t: Try[_]): String = t match {
    case Success(_) => "success"
    case Failure(e) => s"failure: $e"
  }

  def logQuery(q: Any, t0: Long, f: Future[_])(implicit ec: ExecutionContext): Unit = {
    f.onComplete(t => logger.info(s"query $q took ${Platform.currentTime - t0} ms, result ${result(t)}"))
  }

}
