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
      Some("appState=ACTIVE"),
      Some("orgSearchExpanded=true"),
      Some("checkAllApplications=false"),
      Some("start=0"),
      Some("rows=500"),
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

case class ListHakemus(oid: String, state: String, firstNames: String, lastName: String, ssn: String, personOid: String)

case class HakemusHaku(totalCount: Long, results: Seq[ListHakemus])

case class FullHakemus(oid: String, state: String, personOid: String, applicationSystemId: String, vastauksetMerged: Option[Map[String, String]])

// "hakutoiveet":{
// "preference4-Koulutus-id-aoIdentifier":"",
// "preference4-Koulutus-id-educationcode":"",
// "preference1-Opetuspiste":"Ammattiopisto Lappia,  Pop & Jazz Konservatorio Lappia",
// "preference4-Opetuspiste-id":"",
// "preference3-Koulutus-id-athlete":"",
// "preference5-Opetuspiste-id":"",
// "preference4-Koulutus-id-athlete":"",
// "preference2-Koulutus-id-aoIdentifier":"",
// "preference3-Opetuspiste-id":"",
// "preference1-Koulutus-educationDegree":"32",
// "preference5-Koulutus-educationDegree":"",
// "preference2-Koulutus-id-sora":"",
// "preference1_kaksoistutkinnon_lisakysymys":"false",
// "preference4-Koulutus-id-vocational":"",
// "preference3-Koulutus-id":"",
// "preference5-Koulutus-id-lang":"",
// "preference1-Opetuspiste-id":"1.2.246.562.10.10645749713",
// "preference2-Koulutus-educationDegree":"",
// "preference1-Koulutus-id-sora":"false",
// "preference5-Koulutus-id-sora":"",
// "preference1-Koulutus-id-aoIdentifier":"460",
// "preference1-Koulutus-id-educationcode":"koulutus_321204",
// "preference5-Koulutus-id-athlete":"",
// "preference2-Koulutus-id":"",
// "preference1-Koulutus-id-vocational":"true",
// "preference3-Koulutus-id-vocational":"",
// "preference4-Koulutus-educationDegree":"",
// "preference1-Koulutus-id-athlete":"false",
// "preference4-Koulutus-id-kaksoistutkinto":"",
// "preference5-Koulutus-id-kaksoistutkinto":"",
// "preference5-Koulutus-id":"",
// "preference2-Opetuspiste":"",
// "preference2-Koulutus-id-athlete":"",
// "preference3-Koulutus-educationDegree":"",
// "preference2-Koulutus-id-kaksoistutkinto":"",
// "preference5-Opetuspiste":"",
// "preference5-Koulutus-id-educationcode":"",
// "preference3-Koulutus-id-kaksoistutkinto":"",
// "preference1-discretionary":"false",
// "preference4-Koulutus-id-sora":"",
// "preference5-Koulutus-id-vocational":"",
// "preference3-Koulutus-id-lang":"",
// "preference2-Opetuspiste-id":"",
// "preference3-Opetuspiste":"",
// "preference4-Koulutus-id-lang":"",
// "preference1-Opetuspiste-id-parents":"1.2.246.562.10.10645749713,1.2.246.562.10.93483820481,1.2.246.562.10.41253773158,1.2.246.562.10.00000000001,1.2.246.562.10.10645749713",
// "preference2-Koulutus-id-vocational":"",
// "preference1-Koulutus-id":"1.2.246.562.5.31204578244",
// "preference3-Koulutus-id-sora":"",
// "preference4-Koulutus-id":"",
// "preference2-Koulutus-id-lang":"",
// "preference4-Opetuspiste":"",
// "preference3-Koulutus-id-educationcode":"",
// "preference1-Koulutus-id-kaksoistutkinto":"true",
// "preference1-Koulutus":"Musiikin koulutusohjelma, pk (Musiikkialan perustutkinto)",
// "preference3-Koulutus-id-aoIdentifier":"",
// "preference5-Koulutus-id-aoIdentifier":"",
// "preference1-Koulutus-id-lang":"FI",
// "preference2-Koulutus-id-educationcode":""
// }
