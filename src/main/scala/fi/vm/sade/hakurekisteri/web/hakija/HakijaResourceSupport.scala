package fi.vm.sade.hakurekisteri.web.hakija

import akka.util.Timeout
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


  /**
    * Resolves given ApiFormat to ScalaTra content type suffix.
    * @param t: ApiFormat to resolve
    * @return Either the resolved ScalaTra content type or an IllegalArgumentException
    */
  def getContentType(t: ApiFormat): Either[String, IllegalArgumentException] = t match {
    case ApiFormat.Json => Left(formats("json"))
    case ApiFormat.Excel => Left(formats("binary"))
    case ApiFormat.Xml => Left(formats("xml"))
    case tyyppi => Right(new IllegalArgumentException(s"tyyppi $tyyppi is not supported"))
  }

  def getFormatFromTypeParam() = Try(ApiFormat.withName(params("tyyppi"))).getOrElse(ApiFormat.Json)

  def prepareAsyncResult(query: Any, process: Future[Any]) = {
    val t0 = Platform.currentTime
    contentType = getContentType(getFormatFromTypeParam()) match {
      case Left(t) => t
      case Right(ex) => throw ex
    }
    new AsyncResult() {
      override implicit def timeout: Duration = 120.seconds
      val is = process
      process.onFailure { case e: Throwable => logger.error(e, "Exception thrown from async processing") }
    }
  }
}
