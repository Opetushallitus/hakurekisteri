package fi.vm.sade.hakurekisteri.web.hakija

import fi.vm.sade.hakurekisteri.integration.haku.HakuNotFoundException
import fi.vm.sade.hakurekisteri.web.HakuJaValintarekisteriStack
import fi.vm.sade.hakurekisteri.web.rest.support.ApiFormat._
import fi.vm.sade.hakurekisteri.web.rest.support.{ApiFormat, QueryLogging}
import org.scalatra.{ApiFormats, AsyncResult, BadRequest}

import scala.compat.Platform
import scala.concurrent.Future
import scala.concurrent.duration.{Duration, _}
import scala.util.Try
import scala.concurrent.ExecutionContext.Implicits.global

trait HakijaResourceSupport extends ApiFormats with QueryLogging {
  this: HakuJaValintarekisteriStack =>

  /**
    * Resolves given ApiFormat to ScalaTra content type suffix.
    * @param t: ApiFormat to resolve
    * @return Either the resolved ScalaTra content type or an IllegalArgumentException
    */
  def getContentType(t: ApiFormat): Either[String, IllegalArgumentException] = t match {
    case ApiFormat.Json  => Left(formats("json"))
    case ApiFormat.Excel => Left(formats("binary"))
    case ApiFormat.Xml   => Left(formats("xml"))
    case tyyppi          => Right(new IllegalArgumentException(s"tyyppi $tyyppi is not supported"))
  }

  def getFormatFromTypeParam() = Try(ApiFormat.withName(params("tyyppi"))).getOrElse(ApiFormat.Json)

  def prepareAsyncResult(
    query: Any,
    process: Future[Any],
    requestTimeout: Duration = 120.seconds
  ) = {
    val t0 = Platform.currentTime
    contentType = getContentType(getFormatFromTypeParam()) match {
      case Left(t)   => t
      case Right(ex) => throw ex
    }
    new AsyncResult() {
      override implicit def timeout: Duration = requestTimeout
      val is = process
      process.recoverWith { case hnfe: HakuNotFoundException =>
        Future.successful(BadRequest(body = Map("reason" -> hnfe.getMessage)))
      }
      process.failed.foreach { e: Throwable =>
        logger.error(e, "Exception thrown from async processing")
      }
    }
  }
}
