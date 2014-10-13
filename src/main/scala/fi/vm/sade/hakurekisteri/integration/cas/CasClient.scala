package fi.vm.sade.hakurekisteri.integration.cas

import java.io.InterruptedIOException
import java.net.{URLEncoder, URL}
import java.util.concurrent.atomic.AtomicInteger

import com.stackmob.newman.{HttpClient, ApacheHttpClient}
import com.stackmob.newman.dsl._
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}

case class LocationHeaderNotFoundException(m: String) extends Exception(m)

class CasClient(casUrl: Option[String] = None,
                serviceUrl: String,
                user: Option[String] = None,
                password: Option[String] = None)(implicit val httpClient: HttpClient, implicit val ec: ExecutionContext) {

  val logger = LoggerFactory.getLogger(getClass)
  val maxRetries = 3

  private def getTgtUrl: Future[String] = POST(new URL(s"${casUrl.get}/v1/tickets")).
    addHeaders("Content-Type" -> "application/x-www-form-urlencoded").
    setBodyString(s"username=${URLEncoder.encode(user.get, "UTF8")}&password=${URLEncoder.encode(password.get, "UTF8")}").
    apply.map((response) => {
      response.headers match {
        case Some(headers) =>
          headers.list.find(_._1.toLowerCase == "location") match {
            case Some(locationHeader) => locationHeader._2
            case None => throw LocationHeaderNotFoundException("location header not found")
          }
        case None => throw LocationHeaderNotFoundException("location header not found")
      }
    })

  def getProxyTicket: Future[String] = {
    if (casUrl.isEmpty || user.isEmpty || password.isEmpty) throw new IllegalArgumentException("serviceAccessUrl, user or password is not defined")

    val retryCount = new AtomicInteger(1)
    tryProxyTicket(retryCount)
  }

  private def tryProxyTicket(retryCount: AtomicInteger): Future[String] = {
    getTgtUrl.flatMap(tgtUrl => {
      POST(new URL(tgtUrl)).
        addHeaders("Content-Type" -> "application/x-www-form-urlencoded").
        setBodyString(s"service=${URLEncoder.encode(serviceUrl, "UTF-8")}").apply.map(stResponse => {
          val st = stResponse.bodyString.trim
          if (TicketValidator.isValidSt(st)) st
          else throw InvalidServiceTicketException(st)
        })
    }).recoverWith {
      case t: InterruptedIOException =>
        if (retryCount.getAndIncrement <= maxRetries) {
          logger.warn(s"connection error calling cas - trying to retry: $t")
          tryProxyTicket(retryCount)
        } else {
          logger.error(s"connection error calling cas: $t")
          Future.failed(t)
        }

      case t: LocationHeaderNotFoundException =>
        if (retryCount.getAndIncrement <= maxRetries) {
          logger.warn(s"call to cas was not successful - trying to retry: $t")
          tryProxyTicket(retryCount)
        } else {
          logger.error(s"call to cas was not successful: $t")
          Future.failed(t)
        }
    }
  }
}
