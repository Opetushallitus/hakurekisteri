package fi.vm.sade.hakurekisteri.integration.organisaatio

import java.net.URL

import com.stackmob.newman.ApacheHttpClient
import com.stackmob.newman.dsl._
import com.stackmob.newman.response.HttpResponseCode
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}

class RestOrganisaatiopalvelu(serviceUrl: String = "https://itest-virkailija.oph.ware.fi/organisaatio-service")(implicit val ec: ExecutionContext) extends Organisaatiopalvelu {
  val logger = LoggerFactory.getLogger(getClass)

  implicit val httpClient = new ApacheHttpClient()()



  override def getAll: Future[Seq[String]] = {
    val url = new URL(serviceUrl + "/rest/organisaatio")
    logger.info(s"fetching all organizations: $url")
    GET(url).apply.map(response =>
    if (response.code == HttpResponseCode.Ok) {
      response.bodyAsCaseClass[List[String]].toOption.getOrElse(Seq())
    } else {
      logger.error(s"call to organisaatio-service $url failed: ${response.code}")
      throw new RuntimeException(s"virhe kutsuttaessa organisaatiopalvelua: ${response.code}")
    }

    )

  }

  override def get(str: String): Future[Option[Organisaatio]] = {
    val url = new URL(serviceUrl + "/rest/organisaatio/" + str)
    GET(url).apply.map(response => {
      if (response.code == HttpResponseCode.Ok) {
        val organisaatio = response.bodyAsCaseClass[Organisaatio].toOption
        organisaatio
      } else {
        logger.error(s"call to organisaatio-service $url failed: ${response.code}")
        throw new RuntimeException(s"virhe kutsuttaessa organisaatiopalvelua: ${response.code}")
      }
    })
  }

}
