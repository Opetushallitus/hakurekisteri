package fi.vm.sade.hakurekisteri.hakija

import org.json4s._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.Try
import java.net.{URL, URLEncoder}
import com.stackmob.newman.ApacheHttpClient
import com.stackmob.newman.response.{HttpResponseCode, HttpResponse}
import com.stackmob.newman.dsl._
import scala.Some
import fi.vm.sade.hakurekisteri.rest.support.User
import org.slf4j.LoggerFactory

trait Hakupalvelu {

  def find(q: HakijaQuery): Future[Seq[ListHakemus]]

  def get(hakemusOid: String, user: Option[User]): Future[Option[FullHakemus]]

}

class RestHakupalvelu(serviceUrl: String = "https://itest-virkailija.oph.ware.fi/haku-app")(implicit val ec: ExecutionContext) extends Hakupalvelu {
  val logger = LoggerFactory.getLogger(getClass)
  implicit val httpClient = new ApacheHttpClient
  protected implicit def jsonFormats: Formats = DefaultFormats

  def urlencode(s: String): String = URLEncoder.encode(s, "UTF-8")

  def getQueryParams(q: HakijaQuery): String = {
    val params: Seq[String] = Seq(
      Some("appState=ACTIVE"), Some("orgSearchExpanded=true"), Some("checkAllApplications=false"),
      Some("start=0"), Some("rows=500"),
      q.haku.map(s => "asId=" + urlencode(s)),
      q.organisaatio.map(s => "lopoid=" + urlencode(s)),
      q.hakukohdekoodi.map(s => "aoidCode=" + urlencode(s))
    ).flatten

    Try((for(i <- params; p <- List("&", i)) yield p).tail.reduce(_ + _)).getOrElse("")
  }

  def getProxyTicket(user: Option[User]): String = {
    user.flatMap(_.attributePrincipal.map(_.getProxyTicketFor(serviceUrl + "/j_spring_cas_security_check"))).getOrElse("")
  }

  override def find(q: HakijaQuery): Future[Seq[ListHakemus]] = {
    val url = new URL(serviceUrl + "/applications/list/fullName/asc?" + getQueryParams(q))
    val ticket = getProxyTicket(q.user)
    logger.debug("calling haku-app [url={}, ticket={}]", url, ticket)

    GET(url).addHeaders("CasSecurityTicket" -> ticket).apply.map(response => {
      if (response.code == HttpResponseCode.Ok) {
        val hakemusHaku = response.bodyAsCaseClass[HakemusHaku].toOption
        logger.debug("got response: [{}]", hakemusHaku)

        hakemusHaku.map(_.results).getOrElse(Seq())
      } else {
        logger.error("call to haku-app [url={}, ticket={}] failed: {}", url, ticket, response.code)

        throw new RuntimeException("virhe kutsuttaessa hakupalvelua: %s".format(response.code))
      }
    })
  }

  override def get(hakemusOid: String, user: Option[User]): Future[Option[FullHakemus]] = {
    val url = new URL(serviceUrl + "/applications/" + hakemusOid)
    val ticket = getProxyTicket(user)
    logger.debug("calling haku-app [url={}, ticket={}]", url, ticket)

    GET(url).addHeaders("CasSecurityTicket" -> ticket).apply.map(response => {
      if (response.code == HttpResponseCode.Ok) {
        val fullHakemus = response.bodyAsCaseClass[FullHakemus].toOption
        logger.debug("got response: [{}], original body: [{}]", fullHakemus, response.bodyString)

        fullHakemus
      } else {
        logger.error("call to haku-app [url={}, ticket={}] failed: " + response.code, url, ticket)

        throw new RuntimeException("virhe kutsuttaessa hakupalvelua: %s".format(response.code))
      }
    })
  }

}

case class ListHakemus(oid: String)

case class HakemusHaku(totalCount: Long, results: Seq[ListHakemus])

case class FullHakemus(oid: String, personOid: Option[String], applicationSystemId: String, vastauksetMerged: Option[Map[String, String]])
