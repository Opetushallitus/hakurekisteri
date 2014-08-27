package fi.vm.sade.hakurekisteri.integration

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

case class ServiceConfig(serviceAccessUrl: String,
                         serviceUrl: String,
                         user: String,
                         password: String)

class VirkailijaRestClient(config:ServiceConfig)(implicit val httpClient: HttpClient, implicit val ec: ExecutionContext) extends HakurekisteriJsonSupport {

  val serviceAccessUrl: String = config.serviceAccessUrl
  val serviceUrl: String = config.serviceUrl
  val user: String = config.user
  val password: String = config.password

  val logger = LoggerFactory.getLogger(getClass)
  val casClient = new CasClient(serviceAccessUrl, serviceUrl, user, password)

  def executeGet(uri: String): Future[HttpResponse] = {
    casClient.getProxyTicket.flatMap((ticket) => {
      val url = new URL(serviceUrl + uri)
      logger.debug(s"calling url $url with ticket $ticket")
      GET(url).addHeaders("CasSecurityTicket" -> ticket).apply
    })
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

  def readObject[A <: AnyRef: Manifest](uri: String, precondition: (HttpResponseCode) => Boolean): Future[A] = executeGet(uri).map((resp) =>
    if (precondition(resp.code)) resp
    else throw PreconditionFailedException(s"precondition failed for uri: $uri, response code: ${resp.code}")
  ).map(readBody[A])

  def readObject[A <: AnyRef: Manifest](uri: String, okCodes: HttpResponseCode*): Future[A] = readObject[A](uri, (code: HttpResponseCode) => okCodes.contains(code))
}
