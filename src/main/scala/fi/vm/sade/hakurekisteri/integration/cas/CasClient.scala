package fi.vm.sade.hakurekisteri.integration.cas

import java.net.{URLEncoder, URL}

import com.stackmob.newman.ApacheHttpClient
import com.stackmob.newman.dsl._

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

class CasClient(serviceAccessUrl: String, serviceUrl: String, user: Option[String], password: Option[String])(implicit val ec: ExecutionContext) {
  implicit val httpClient = new ApacheHttpClient(socketTimeout = 120.seconds.toMillis.toInt)()

  def getProxyTicket: Future[String] = (user, password) match {
    case (Some(u), Some(p)) =>
      POST(new URL(s"$serviceAccessUrl/accessTicket")).
        addHeaders("Content-Type" -> "application/x-www-form-urlencoded").
        setBodyString(s"client_id=${URLEncoder.encode(u, "UTF8")}&client_secret=${URLEncoder.encode(p, "UTF8")}&service_url=${URLEncoder.encode(serviceUrl, "UTF8")}").
        apply.map((response) => {
          val st = response.bodyString.trim
          if (TicketValidator.isValidSt(st)) st
          else throw InvalidServiceTicketException(st)
        })
    case _ => throw new IllegalArgumentException(s"user and password not provided")
  }
}
