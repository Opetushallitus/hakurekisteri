package fi.vm.sade.hakurekisteri.hakija

import com.stackmob.newman._
import com.stackmob.newman.dsl._
import scala.concurrent._
import scala.concurrent.duration._
import java.net.URL
import com.stackmob.newman.response.HttpResponse


trait Organisaatiopalvelu {

  def get(str: String): Option[Organisaatio]

}

case class Organisaatio(oid: String, nimi: Map[String, String], toimipistekoodi: String, oppilaitosKoodi: String)

class RestOrganisaatiopalvelu(serviceUrl: String = "https://itest-virkailija.oph.ware.fi/organisaatio-service") extends Organisaatiopalvelu {
  implicit val httpClient = new ApacheHttpClient

  override def get(str: String): Option[Organisaatio] = {
    val url = new URL(serviceUrl + "/rest/organisaatio/" + str)
    val response: HttpResponse = Await.result(GET(url).apply, 10.second)
    response.bodyAsCaseClass[Organisaatio].toOption
  }

}
