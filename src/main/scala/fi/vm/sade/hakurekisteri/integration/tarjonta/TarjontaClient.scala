package fi.vm.sade.hakurekisteri.integration.tarjonta

import java.net.{URLEncoder, URL}

import com.stackmob.newman.HttpClient
import com.stackmob.newman.dsl._
import com.stackmob.newman.response.{HttpResponse, HttpResponseCode}
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}

class TarjontaClient(serviceUrl: String)(implicit val httpClient: HttpClient, implicit val ec: ExecutionContext) {
  val logger = LoggerFactory.getLogger(getClass)

  def searchKomo(koulutuskoodi: String, opintoala1995: Option[String], koulutusala2002: Option[String]): Future[Option[Komo]] = {
    val url = new URL(s"$serviceUrl/rest/v1/komo/search?koulutus=${URLEncoder.encode(koulutuskoodi, "UTF-8")}")
    GET(url).apply.map((response: HttpResponse) => {
      if (response.code == HttpResponseCode.Ok) {
        response.bodyAsCaseClass[TarjontaSearchResponse].toOption match {
          case Some(r) => r.result.filter(filterKomo(opintoala1995, koulutusala2002)).headOption
          case _ => None
        }
      } else {
        logger.error(s"call to tarjonta-service $serviceUrl failed: ${response.code}")
        throw TarjontaConnectionErrorException(s"got non-ok response from tarjonta: ${response.code}, response: ${response.bodyString}")
      }
    })
  }

  def filterKomo(opintoala1995: Option[String], koulutusala2002: Option[String])(komo: Komo): Boolean = {
    (opintoala1995, koulutusala2002) match {
      case (Some(opintoala), None) => komo.opintoala.uri.startsWith("opintoalaoph1995") && komo.opintoala.arvo == opintoala
      case (None, Some(koulutusala)) => komo.koulutusala.uri.startsWith("koulutusalaoph2002") && komo.koulutusala.arvo == koulutusala
      case _ => true
    }
  }
}

case class TarjontaSearchResponse(result: Seq[Komo])