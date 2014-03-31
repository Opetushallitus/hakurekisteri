package fi.vm.sade.hakurekisteri.hakija

import fi.vm.sade.generic.rest.CachingRestClient
import org.json4s._
import org.json4s.jackson.JsonMethods._
import scala.concurrent.{ExecutionContext, Future}
import java.io.Serializable
import scala.util.Try
import java.net.URLEncoder

trait Hakupalvelu {

  def find(q: HakijaQuery): Future[Seq[SmallHakemus]]

  def get(hakemusOid: String): Future[Option[FullHakemus]]

}

class RestHakupalvelu(serviceUrl: String = "https://itest-virkailija.oph.ware.fi/haku-app",
                      webCasUrl: String = "https://itest-virkailija.oph.ware.fi/cas")(implicit val ec: ExecutionContext) extends Hakupalvelu {
  val cachingRestClient = new CachingRestClient
  cachingRestClient.setUseProxyAuthentication(false)
  cachingRestClient.setWebCasUrl(webCasUrl)
  cachingRestClient.setCasService(serviceUrl + "/j_spring_cas_security_check")
  cachingRestClient.setUsername("robotti")
  cachingRestClient.setPassword("Testaaja!")

  protected implicit def jsonFormats: Formats = DefaultFormats

  def urlencode(s: String): String = URLEncoder.encode(s, "UTF-8")

  def extractQueryParams(q: HakijaQuery): String = {
    val params: Seq[String] = Seq(
      q.haku.map(s => "asId=" + urlencode(s)),
      q.organisaatio.map(s => "lopoid=" + urlencode(s)),
      q.hakukohdekoodi.map(s => "hakukohdekoodi=" + urlencode(s))
    ).flatten

    Try((for(i <- params; p <- List("&", i)) yield p).tail.reduce(_ + _)).getOrElse("")
  }

  override def find(q: HakijaQuery): Future[Seq[SmallHakemus]] = {
    Future(parse(cachingRestClient.get(serviceUrl + "/applications/list/fullName/asc?appState=ACTIVE&" + extractQueryParams(q) +
      "&orgSearchExpanded=true&checkAllApplications=false&start=0&rows=500")).extract[HakemusHaku].results)
  }

  override def get(hakemusOid: String): Future[Option[FullHakemus]] = {
    Future(Some(parse(cachingRestClient.get(serviceUrl + "/applications/" + hakemusOid)).extract[FullHakemus]))
  }

}

case class SmallHakemus(oid: String, state: String, firstNames: String, lastName: String, ssn: String, personOid: String)

case class HakemusHaku(totalCount: Long, results: Seq[SmallHakemus])

case class Henkilotiedot(kansalaisuus: String, asuinmaa: String, matkapuhelinnumero1: String, Sukunimi: String, Henkilotunnus: String,
                         Postinumero: String, lahiosoite: String, sukupuoli: String, Sähköposti: String, Kutsumanimi: String, Etunimet: String,
                         kotikunta: String, aidinkieli: String, syntymaaika: String, onkoSinullaSuomalainenHetu: Option[Boolean])

case class Koulutustausta(PK_PAATTOTODISTUSVUOSI: String, POHJAKOULUTUS: String, perusopetuksen_kieli: String, lahtokoulu: Option[String], lahtoluokka: Option[String], luokkataso: String)

case class Lisatiedot(lupaMarkkinointi: Boolean, lupaJulkaisu: Option[Boolean])

case class Answers(henkilotiedot: Option[Henkilotiedot], koulutustausta: Koulutustausta, hakutoiveet: Option[Map[String, String]], lisatiedot: Option[Lisatiedot])

case class FullHakemus(oid: String, state: String, personOid: String, applicationSystemId: String,
                           studentOid: String, received: Long, updated: Long, answers: Option[Answers]) {
  def toSmallHakemus: SmallHakemus = {
    SmallHakemus(oid, state, answers.get.henkilotiedot.get.Etunimet, answers.get.henkilotiedot.get.Sukunimi, answers.get.henkilotiedot.get.Henkilotunnus, personOid)
  }
}

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
