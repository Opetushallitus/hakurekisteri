package fi.vm.sade.hakurekisteri.web.hakija

import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.web.rest.support.{QueryLogging, ApiFormat}
import fi.vm.sade.hakurekisteri.web.rest.support.ApiFormat._
import org.scalatra.{AsyncResult, ApiFormats, ScalatraServlet, ScalatraBase}

import scala.compat.Platform
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.Try
import scala.concurrent.duration._


trait HakijaResourceSupport extends ApiFormats with QueryLogging { this: HakuJaValintarekisteriStack =>

  def getContentType(t: ApiFormat): String = t match {
    case ApiFormat.Json => formats("json")
    case ApiFormat.Excel => formats("binary")
    case tyyppi => throw new IllegalArgumentException(s"tyyppi $tyyppi is not supported")
  }

  def getFormatFromTypeParam() = Try(ApiFormat.withName(params("tyyppi"))).getOrElse(ApiFormat.Json)

  def prepareAsyncResult(query: Any, process: Future[Any]) = {
    val t0 = Platform.currentTime
    new AsyncResult() {
      override implicit def timeout: Duration = 120.seconds

      override val contentType = getContentType(getFormatFromTypeParam())

      val is = process
    }
  }
}
