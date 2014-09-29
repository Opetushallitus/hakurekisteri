package fi.vm.sade.hakurekisteri.integration

import java.io.InterruptedIOException
import java.net.URL

import com.stackmob.newman.HttpClient
import com.stackmob.newman.dsl._
import com.stackmob.newman.response.{HttpResponseCode, HttpResponse}
import fi.vm.sade.hakurekisteri.integration.cas.CasClient
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import org.slf4j.LoggerFactory

import scala.concurrent.{Future, ExecutionContext}
import scala.util.Try

case class PreconditionFailedException(message: String) extends Exception(message)

case class ServiceConfig(serviceAccessUrl: Option[String] = None,
                         serviceUrl: String,
                         user: Option[String] = None,
                         password: Option[String] = None)

class VirkailijaRestClient(config: ServiceConfig)(implicit val httpClient: HttpClient, implicit val ec: ExecutionContext) extends HakurekisteriJsonSupport {

  val serviceAccessUrl: Option[String] = config.serviceAccessUrl
  val serviceUrl: String = config.serviceUrl
  val user: Option[String] = config.user
  val password: Option[String] = config.password

  val logger = LoggerFactory.getLogger(getClass)
  val casClient = new CasClient(serviceAccessUrl, serviceUrl, user, password)

  def logConnectionFailure[T](f: Future[T], url: URL) = f.onFailure {
    case t: InterruptedIOException => logger.error(s"connection error calling url [$url]: $t")
  }

  def executeGet(uri: String): Future[(HttpResponse, Option[String])]= {
    val url = new URL(serviceUrl + uri)
    (user, password) match {
      case (None, None) =>
        //logger.debug(s"calling url $url");
        val f = GET(url).apply.map((_, None))
        logConnectionFailure(f, url)
        f
      case (Some(u), Some(p)) =>
        casClient.getProxyTicket.flatMap((ticket) => {
          //logger.debug(s"calling url $url with ticket $ticket");
          val f = GET(url).addHeaders("CasSecurityTicket" -> ticket).apply.map((_, Some(ticket)))
          logConnectionFailure(f, url)
          f
        })
      case _ => throw new IllegalArgumentException("either user or password is not defined")
    }
  }

  def tryReadBody[A <: AnyRef: Manifest](response: HttpResponse): Try[A] = {
    import org.json4s.jackson.Serialization.read
    Try(read[A](response.bodyString))
  }

  def readBody[A <: AnyRef: Manifest](response: HttpResponse): A = {
    val rawResult = tryReadBody[A](response)
    if (rawResult.isFailure) logger.warn("Failed to deserialize", rawResult.failed.get)
    rawResult.get
  }

  def readObject[A <: AnyRef: Manifest](uri: String, precondition: (HttpResponseCode) => Boolean): Future[A] = executeGet(uri).map{case (resp, ticket) =>
    if (precondition(resp.code)) resp
    else throw PreconditionFailedException(s"precondition failed for uri: $uri, response code: ${resp.code} with ${ticket.map("ticket: " + _).getOrElse("no ticket")}")
  }.map(readBody[A])

  def readObject[A <: AnyRef: Manifest](uri: String, okCodes: HttpResponseCode*): Future[A] = {
    val codes = if (okCodes.isEmpty) Seq(HttpResponseCode.Ok) else okCodes
    readObject[A](uri, (code: HttpResponseCode) => codes.contains(code))
  }
}
