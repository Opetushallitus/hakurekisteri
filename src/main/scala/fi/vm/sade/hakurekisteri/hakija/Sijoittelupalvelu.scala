package fi.vm.sade.hakurekisteri.hakija

import scala.concurrent.{ExecutionContext, Future}
import org.slf4j.LoggerFactory
import com.stackmob.newman.ApacheHttpClient
import org.json4s.{DefaultFormats, Formats}
import java.net.URL
import com.stackmob.newman.dsl._
import fi.vm.sade.hakurekisteri.rest.support.User
import com.stackmob.newman.response.HttpResponseCode
import SijoitteluValintatuloksenTila.SijoitteluValintatuloksenTila
import SijoitteluHakemuksenTila.SijoitteluHakemuksenTila

object SijoitteluValintatuloksenTila extends Enumeration {
  type SijoitteluValintatuloksenTila = Value
  val ILMOITETTU, VASTAANOTTANUT, VASTAANOTTANUT_LASNA, VASTAANOTTANUT_POISSAOLEVA,
    EI_VASTAANOTETTU_MAARA_AIKANA, PERUNUT, PERUUTETTU, EHDOLLISESTI_VASTAANOTTANUT = Value
}

object SijoitteluHakemuksenTila extends Enumeration {
  type SijoitteluHakemuksenTila = Value
  val HYLATTY, VARALLA, PERUUNTUNUT, HYVAKSYTTY, PERUNUT, PERUUTETTU = Value
}

case class SijoitteluPistetieto(osallistuminen: Option[String], laskennallinenArvo: Option[String], arvo: Option[String], tunniste: Option[String])

case class SijoitteluHakutoiveenValintatapajono(varalla: Option[Int], hyvaksytty: Option[Int], hakeneet: Option[Int], alinHyvaksyttyPistemaara: Option[BigDecimal],
                                                 pisteet: Option[BigDecimal], tasasijaJonosija: Option[BigDecimal], hyvaksyttyHarkinnanvaraisesti: Option[Boolean],
                                                 vastaanottotieto: Option[SijoitteluValintatuloksenTila], tilanKuvaukset: Option[Map[String, String]],
                                                 tila: Option[SijoitteluHakemuksenTila], varasijanNumero: Option[Int], paasyJaSoveltuvuusKokeenTulos: Option[BigDecimal],
                                                 jonosija: Option[Int], valintatapajonoNimi: Option[String], valintatapajonoOid: Option[String],
                                                 valintatapajonoPrioriteetti: Option[Int])

case class SijoitteluHakutoive(hakutoiveenValintatapajonot: Option[SijoitteluHakutoiveenValintatapajono], pistetiedot: Option[Seq[SijoitteluPistetieto]],
                                tarjoajaOid: Option[String], hakukohdeOid: Option[String], hakutoive: Option[Int])

case class SijoitteluHakija(hakutoiveet: Option[SijoitteluHakutoive], sukunimi: Option[String], etunimi: Option[String], hakemusOid: Option[String])

case class SijoitteluPagination(results: Option[Seq[SijoitteluHakija]], totalCount: Option[Int])

trait Sijoittelupalvelu {

  def getSijoitteluTila(hakuOid: String, user: Option[User]): Future[Option[SijoitteluPagination]]

}

class RestSijoittelupalvelu(serviceUrl: String = "https://itest-virkailija.oph.ware.fi/sijoittelu-service")(implicit val ec: ExecutionContext) extends Sijoittelupalvelu {
  val logger = LoggerFactory.getLogger(getClass)
  implicit val httpClient = new ApacheHttpClient()()
  protected implicit def jsonFormats: Formats = DefaultFormats

  def getProxyTicket(user: Option[User]): String = {
    user.flatMap(_.attributePrincipal.map(_.getProxyTicketFor(serviceUrl + "/j_spring_cas_security_check"))).getOrElse("")
  }

  override def getSijoitteluTila(hakuOid: String, user: Option[User]): Future[Option[SijoitteluPagination]] = {
    val url = new URL(serviceUrl + "/resources/sijoittelu/" + hakuOid + "/sijoitteluajo/latest/hakemukset")
    val ticket = getProxyTicket(user)
    logger.debug("calling sijoittelu-service [url={}, ticket={}]", Array(url, ticket))
    GET(url).addHeaders("CasSecurityTicket" -> ticket).apply.map(response => {
      if (response.code == HttpResponseCode.Ok) {
        val hakemus = response.bodyAsCaseClass[SijoitteluPagination].toOption
        logger.debug("got response: [{}]", hakemus)
        hakemus
      } else {
        logger.error("call to sijoittelu-service [url={}, ticket={}] failed: {}", url, ticket, response.code)
        throw new RuntimeException("virhe kutsuttaessa sijoittelupalvelua: %s".format(response.code))
      }
    })
  }

}

