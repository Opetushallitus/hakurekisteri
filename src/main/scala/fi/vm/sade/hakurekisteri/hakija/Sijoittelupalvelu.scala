package fi.vm.sade.hakurekisteri.hakija

import scala.concurrent.{ExecutionContext, Future}
import org.slf4j.LoggerFactory
import com.stackmob.newman.ApacheHttpClient
import java.net.{URLEncoder, URL}
import com.stackmob.newman.dsl._
import com.stackmob.newman.response.{HttpResponse, HttpResponseCode}
import akka.actor.{Cancellable, Actor}
import scala.compat.Platform
import scala.util.Try
import org.json4s.jackson.Serialization._
import fi.vm.sade.hakurekisteri.hakija.SijoitteluHakutoive
import fi.vm.sade.hakurekisteri.hakija.SijoitteluHakija
import scala.Some
import fi.vm.sade.hakurekisteri.hakija.SijoitteluPistetieto
import fi.vm.sade.hakurekisteri.hakija.SijoitteluHakutoiveenValintatapajono
import fi.vm.sade.hakurekisteri.hakija.SijoitteluPagination
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport

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
                                                 vastaanottotieto: Option[String], tilanKuvaukset: Option[Map[String, String]],
                                                 tila: Option[String], varasijanNumero: Option[Int], paasyJaSoveltuvuusKokeenTulos: Option[BigDecimal],
                                                 jonosija: Option[Int], valintatapajonoNimi: Option[String], valintatapajonoOid: Option[String],
                                                 valintatapajonoPrioriteetti: Option[Int])

case class SijoitteluHakutoive(hakutoiveenValintatapajonot: Option[Seq[SijoitteluHakutoiveenValintatapajono]], pistetiedot: Option[Seq[SijoitteluPistetieto]],
                                tarjoajaOid: Option[String], hakukohdeOid: Option[String], hakutoive: Option[Int])

case class SijoitteluHakija(hakutoiveet: Option[Seq[SijoitteluHakutoive]], sukunimi: Option[String], etunimi: Option[String], hakemusOid: Option[String])

case class SijoitteluPagination(results: Option[Seq[SijoitteluHakija]], totalCount: Option[Int])

trait Sijoittelupalvelu {

  def getSijoitteluTila(hakuOid: String): Future[Option[SijoitteluPagination]]

}


case class SijoitteluTulos(tilat: Map[String, Map[String, String]], pisteet: Map[String, Map[String, BigDecimal]])


/**
 * Created by verneri on 12.6.2014.
 */

case class SijoitteluQuery(hakuOid: String)

class RestSijoittelupalvelu(serviceAccessUrl: String, serviceUrl: String = "https://itest-virkailija.oph.ware.fi/sijoittelu-service", user: Option[String], password: Option[String])(implicit val ec: ExecutionContext) extends Sijoittelupalvelu with HakurekisteriJsonSupport {
  val logger = LoggerFactory.getLogger(getClass)
  import scala.concurrent.duration._
  implicit val httpClient = new ApacheHttpClient(socketTimeout = 120.seconds.toMillis.toInt)()


  def getProxyTicket: Future[String] = (user, password) match {
    case (Some(u), Some(p)) =>
      POST(new URL(s"$serviceAccessUrl/accessTicket")).
        addHeaders("Content-Type" -> "application/x-www-form-urlencoded").
        setBodyString(s"client_id=${URLEncoder.encode(u, "UTF8")}&client_secret=${URLEncoder.encode(p, "UTF8")}&service_url=${URLEncoder.encode(serviceUrl, "UTF8")}").
        apply.map((response) => response.bodyString.trim)
    case _ => Future.successful("")
  }

  def readBody[A <: AnyRef](response: HttpResponse): Option[A] = {
    import org.json4s.jackson.Serialization.read
    val rawResult = Try(read[A](response.bodyString))

    if (rawResult.isFailure) logger.warn("Failed to deserialize", rawResult.failed.get)

    val result = rawResult.toOption
    result
  }

  override def getSijoitteluTila(hakuOid: String): Future[Option[SijoitteluPagination]] = {
    val url = new URL(serviceUrl + "/resources/sijoittelu/" + hakuOid + "/sijoitteluajo/latest/hakemukset")
    getProxyTicket.flatMap((ticket) => {
      logger.debug("calling sijoittelu-service [url={}, ticket={}]", Seq(url, ticket):_*)
      GET(url).addHeaders("CasSecurityTicket" -> ticket).apply.map(response => {
      if (response.code == HttpResponseCode.Ok) {


        val sijoitteluTulos = readBody[SijoitteluPagination](response)
        logger.debug("got response from [url={}, ticket={}]", Seq(url, ticket):_*)

        sijoitteluTulos
      } else {
        logger.error("call to sijoittelu-service [url={}, ticket={}] failed: {}", url, ticket, response.code)
        throw new RuntimeException("virhe kutsuttaessa sijoittelupalvelua: %s".format(response.code))
      }
    })})
  }

}

