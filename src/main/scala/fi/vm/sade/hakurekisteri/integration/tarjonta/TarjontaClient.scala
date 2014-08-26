package fi.vm.sade.hakurekisteri.integration.tarjonta

import java.net.{URLEncoder, URL}

import com.stackmob.newman.HttpClient
import com.stackmob.newman.dsl._
import com.stackmob.newman.response.{HttpResponse, HttpResponseCode}
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}

class TarjontaClient(serviceUrl: String)(implicit val httpClient: HttpClient, implicit val ec: ExecutionContext) {
  val logger = LoggerFactory.getLogger(getClass)

  def searchKomo(koulutuskoodi: String): Future[Seq[Komo]] = {
    val url = new URL(s"$serviceUrl/rest/v1/komo/search?koulutus=${URLEncoder.encode(koulutuskoodi, "UTF-8")}")
    GET(url).apply.map((response: HttpResponse) => {
      if (response.code == HttpResponseCode.Ok) {
        response.bodyAsCaseClass[TarjontaSearchResponse].toOption match {
          case Some(r) => r.result
          case _ => Seq()
        }
      } else {
        logger.error(s"call to tarjonta-service $url failed: ${response.code}")
        throw TarjontaConnectionErrorException(s"got non-ok response from tarjonta: ${response.code}, response: ${response.bodyString}")
      }
    })
  }

  def getKomo(oid: String): Future[Option[Komo]] = {
    val url = new URL(s"$serviceUrl/rest/v1/komo/${URLEncoder.encode(oid, "UTF-8")}")
    GET(url).apply.map((response: HttpResponse) => {
      if (response.code == HttpResponseCode.Ok) {
        response.bodyAsCaseClass[TarjontaKomoResponse].toOption.map((r) => r.result)
      } else {
        logger.error(s"call to tarjonta-service $url failed: ${response.code}")
        throw TarjontaConnectionErrorException(s"got non-ok response from tarjonta: ${response.code}, response: ${response.bodyString}")
      }
    })
  }
}

case class TarjontaSearchResponse(result: Seq[Komo])

case class TarjontaKomoResponse(result: Komo)