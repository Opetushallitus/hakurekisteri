package fi.vm.sade.hakurekisteri.hakija

import com.stackmob.newman._
import com.stackmob.newman.dsl._
import scala.concurrent._
import scala.concurrent.duration._
import java.net.URL
import com.stackmob.newman.response.{HttpResponseCode, HttpResponse}
import org.slf4j.LoggerFactory


trait Organisaatiopalvelu {

  def get(str: String): Future[Option[Organisaatio]]

}

case class Organisaatio(oid: String, nimi: Map[String, String], toimipistekoodi: Option[String], oppilaitosKoodi: Option[String], parentOid: Option[String])

class RestOrganisaatiopalvelu(serviceUrl: String = "https://itest-virkailija.oph.ware.fi/organisaatio-service")(implicit val ec: ExecutionContext) extends Organisaatiopalvelu {
  val logger = LoggerFactory.getLogger(getClass)

  implicit val httpClient = new ApacheHttpClient

  override def get(str: String): Future[Option[Organisaatio]] = {
    val url = new URL(serviceUrl + "/rest/organisaatio/" + str)
    logger.debug("calling organisaatio-service [{}]", url)
    GET(url).apply.map(response => {
      if (response.code != HttpResponseCode.Ok) {
        logger.error("call to organisaatio-service [{}] failed: {}", url, response.code)
        throw new RuntimeException("virhe kutsuttaessa organisaatiopalvelua: %s".format(response.code))
      } else {
        val organisaatio = response.bodyAsCaseClass[Organisaatio].toOption
        logger.debug("got response: [{}]", organisaatio)
        organisaatio
      }
    })
  }

}
