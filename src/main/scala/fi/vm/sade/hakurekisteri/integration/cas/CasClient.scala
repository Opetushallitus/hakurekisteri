package fi.vm.sade.hakurekisteri.integration.cas

import java.io.InterruptedIOException
import java.net.{URLEncoder, URL}
import java.util.concurrent.atomic.AtomicInteger

import com.stackmob.newman.response.HttpResponseCode
import com.stackmob.newman.{HttpClient, ApacheHttpClient}
import com.stackmob.newman.dsl._
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}

sealed class CasException(m: String) extends Exception(m)
case class InvalidServiceTicketException(m: String) extends CasException(s"service ticket is not valid: $m")
case class STWasNotCreatedException(m: String) extends CasException(m)
case class TGTWasNotCreatedException(m: String) extends CasException(m)
case class LocationHeaderNotFoundException(m: String) extends CasException(m)

class CasClient(casUrl: Option[String] = None,
                serviceUrl: String,
                user: Option[String] = None,
                password: Option[String] = None)(implicit val httpClient: HttpClient, implicit val ec: ExecutionContext) {

  //val logger = LoggerFactory.getLogger(getClass)
  val maxRetries = 3
  val serviceUrlSuffix = "/j_spring_cas_security_check"

  private def getTgtUrl: Future[String] = POST(new URL(s"${casUrl.get}/v1/tickets")).
    addHeaders("Content-Type" -> "application/x-www-form-urlencoded").
    setBodyString(s"username=${URLEncoder.encode(user.get, "UTF8")}&password=${URLEncoder.encode(password.get, "UTF8")}").
    apply.map((response) => {
      if (response.code == HttpResponseCode.Created) response.headers match {
        case Some(headers) =>
          headers.list.find(_._1 == "Location") match {
            case Some(locationHeader) => locationHeader._2
            case None => throw LocationHeaderNotFoundException("location header not found")
          }
        case None => throw LocationHeaderNotFoundException("location header not found")
      } else throw TGTWasNotCreatedException(s"got non ok response code from cas: ${response.code}")
    })

  def getProxyTicket: Future[String] = {
    if (casUrl.isEmpty || user.isEmpty || password.isEmpty) throw new IllegalArgumentException("casUrl, user or password is not defined")

    val retryCount = new AtomicInteger(1)
    tryProxyTicket(retryCount)
  }

  private def postfixServiceUrl(url: String): String = url match {
    case s if !s.endsWith(serviceUrlSuffix) => s"$url$serviceUrlSuffix"
    case s => s
  }

  private def tryProxyTicket(retryCount: AtomicInteger): Future[String] = getTgtUrl.flatMap(tgtUrl => {
    POST(new URL(tgtUrl)).
      addHeaders("Content-Type" -> "application/x-www-form-urlencoded").
      setBodyString(s"service=${URLEncoder.encode(postfixServiceUrl(serviceUrl), "UTF-8")}").apply.map(stResponse => {
        if (stResponse.code == HttpResponseCode.Ok) {
          val st = stResponse.bodyString.trim
          if (TicketValidator.isValidSt(st)) st
          else throw InvalidServiceTicketException(st)
        } else throw STWasNotCreatedException(s"got non ok response from cas: ${stResponse.code}")
      })
  }).recoverWith {
    case t: InterruptedIOException =>
      if (retryCount.getAndIncrement <= maxRetries) {
        //logger.warn(s"connection error calling cas - trying to retry: $t")
        tryProxyTicket(retryCount)
      } else {
        //logger.error(s"connection error calling cas: $t")
        Future.failed(t)
      }

    case t: LocationHeaderNotFoundException =>
      if (retryCount.getAndIncrement <= maxRetries) {
        //logger.warn(s"call to cas was not successful - trying to retry: $t")
        tryProxyTicket(retryCount)
      } else {
        //logger.error(s"call to cas was not successful: $t")
        Future.failed(t)
      }
  }
}
