package fi.vm.sade.hakurekisteri.integration.cas

import java.io.InterruptedIOException
import java.net.{URLEncoder, URL}

import com.stackmob.newman.{HttpClient, ApacheHttpClient}
import com.stackmob.newman.dsl._
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}

class CasClient(serviceAccessUrl: Option[String] = None,
                serviceUrl: String,
                user: Option[String] = None,
                password: Option[String] = None)(implicit val httpClient: HttpClient, implicit val ec: ExecutionContext) {

  val logger = LoggerFactory.getLogger(getClass)

  def getProxyTicket: Future[String] = {
    if (serviceAccessUrl.isEmpty || user.isEmpty || password.isEmpty) throw new IllegalArgumentException("serviceAccessUrl, user or password is not defined")

    val f = POST(new URL(s"${serviceAccessUrl.get}/accessTicket")).
      addHeaders("Content-Type" -> "application/x-www-form-urlencoded").
      setBodyString(s"client_id=${URLEncoder.encode(user.get, "UTF8")}&client_secret=${URLEncoder.encode(password.get, "UTF8")}&service_url=${URLEncoder.encode(serviceUrl, "UTF8")}").
      apply.map((response) => {
      val st = response.bodyString.trim
      if (TicketValidator.isValidSt(st)) st
      else throw InvalidServiceTicketException(st)
    })

    f.onFailure {
      case t: InterruptedIOException => logger.error(s"connection error calling cas: $t")
    }
    
    f
  }
}
