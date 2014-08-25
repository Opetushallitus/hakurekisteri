package fi.vm.sade.hakurekisteri.integration.cas

import java.net.{URLEncoder, URL}

import com.stackmob.newman.ApacheHttpClient
import com.stackmob.newman.dsl._

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

class CasClient(serviceAccessUrl: String, serviceUrl: String, user: String, password: String)(implicit val ec: ExecutionContext) {
  implicit val httpClient = new ApacheHttpClient(socketTimeout = 120.seconds.toMillis.toInt)()

  def getProxyTicket: Future[String] = POST(new URL(s"$serviceAccessUrl/accessTicket")).
    addHeaders("Content-Type" -> "application/x-www-form-urlencoded").
    setBodyString(s"client_id=${URLEncoder.encode(user, "UTF8")}&client_secret=${URLEncoder.encode(password, "UTF8")}&service_url=${URLEncoder.encode(serviceUrl, "UTF8")}").
    apply.map((response) => {
      val st = response.bodyString.trim
      if (TicketValidator.isValidSt(st)) st
      else throw InvalidServiceTicketException(st)
    })
}
