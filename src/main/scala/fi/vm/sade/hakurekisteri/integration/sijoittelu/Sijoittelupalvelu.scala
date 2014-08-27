package fi.vm.sade.hakurekisteri.integration.sijoittelu

import java.net.{URL, URLEncoder}

import com.stackmob.newman.ApacheHttpClient
import com.stackmob.newman.dsl._
import com.stackmob.newman.response.{HttpResponse, HttpResponseCode}
import fi.vm.sade.hakurekisteri.integration.cas.{CasClient, TicketValidator, InvalidServiceTicketException}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.hakurekisteri.integration.sijoittelu.SijoitteluValintatuloksenTila.SijoitteluValintatuloksenTila
import fi.vm.sade.hakurekisteri.integration.sijoittelu.SijoitteluHakemuksenTila.SijoitteluHakemuksenTila
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

object SijoitteluValintatuloksenTila extends Enumeration {
  type SijoitteluValintatuloksenTila = Value
  val ILMOITETTU, VASTAANOTTANUT, VASTAANOTTANUT_LASNA, VASTAANOTTANUT_POISSAOLEVA,
    EI_VASTAANOTETTU_MAARA_AIKANA, PERUNUT, PERUUTETTU, EHDOLLISESTI_VASTAANOTTANUT = Value

  def valueOption(t: String): Option[SijoitteluValintatuloksenTila.Value] = {
    Try(withName(t)).toOption
  }
}

object SijoitteluHakemuksenTila extends Enumeration {
  type SijoitteluHakemuksenTila = Value
  val HYLATTY, VARALLA, PERUUNTUNUT, HYVAKSYTTY, PERUNUT, PERUUTETTU = Value

  def valueOption(t: String): Option[SijoitteluHakemuksenTila.Value] = {
    Try(withName(t)).toOption
  }
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

case class SijoitteluPagination(results: Seq[SijoitteluHakija], totalCount: Int)

trait Sijoittelupalvelu {
  def getSijoitteluTila(hakuOid: String): Future[SijoitteluPagination]
}

trait SijoitteluTulos {
  def pisteet(hakemus: String, kohde: String): Option[BigDecimal]

  def hakemus(hakemus: String, kohde: String): Option[SijoitteluHakemuksenTila]

  def valinta(hakemus: String, kohde: String): Option[SijoitteluValintatuloksenTila]
}

object SijoitteluTulos {
  def getValintatapaMap[A](shakijas: Seq[SijoitteluHakija], extractor: (SijoitteluHakutoiveenValintatapajono) => Option[A]): Map[String, Map[String, A]] = shakijas.groupBy(_.hakemusOid).collect {
    case (Some(hakemusOid), sijoitteluHakijas) =>
      def getIndex(toive: SijoitteluHakutoive): Option[(String, A)] = {
        (toive.hakukohdeOid, toive.hakutoiveenValintatapajonot.flatMap(_.headOption)) match {
          case (Some(hakukohde), Some(vtjono)) => extractor(vtjono).map((hakukohde, _))
          case _ => None
        }
      }

      (hakemusOid, (for (hakija <- sijoitteluHakijas;
                         toive <- hakija.hakutoiveet.getOrElse(Seq())) yield getIndex(toive)).flatten.toMap)
  }

  def tilat(vtj: SijoitteluHakutoiveenValintatapajono): Option[(SijoitteluHakemuksenTila, Option[SijoitteluValintatuloksenTila])] = {
    for (
      hakemus <- hakemuksenTila(vtj)
    ) yield (hakemus, valinnantila(vtj))
  }

  def hakemuksenTila(vtj: SijoitteluHakutoiveenValintatapajono): Option[SijoitteluHakemuksenTila] = for (
    hakemusRaw <- vtj.tila;
    hakemus <- SijoitteluHakemuksenTila.valueOption(hakemusRaw)
  ) yield hakemus

  def valinnantila(vtj: SijoitteluHakutoiveenValintatapajono): Option[SijoitteluValintatuloksenTila] = vtj.vastaanottotieto.flatMap(SijoitteluValintatuloksenTila.valueOption)

  def apply(shs:Seq[SijoitteluHakija]) = new SijoitteluTulos {

    private val sijoitteluTilat = getValintatapaMap(shs, tilat)
    private val pistetila: Map[String, Map[String, BigDecimal]] = getValintatapaMap(shs, _.pisteet)

    override def pisteet(hakemus: String, kohde: String): Option[BigDecimal] = pistetila.getOrElse(hakemus, Map()).get(kohde)

    override def hakemus(hakemus: String, kohde: String): Option[SijoitteluHakemuksenTila] = {
      sijoitteluTilat.getOrElse(hakemus, Map()).get(kohde).map(_._1)
    }

    override def valinta(hakemus: String, kohde: String): Option[SijoitteluValintatuloksenTila] = {
      sijoitteluTilat.getOrElse(hakemus, Map()).get(kohde).flatMap(_._2)
    }
  }
}

case class SijoitteluQuery(hakuOid: String)

class RestSijoittelupalvelu(serviceAccessUrl: Option[String], serviceUrl: String = "https://itest-virkailija.oph.ware.fi/sijoittelu-service", user: Option[String], password: Option[String])(implicit val ec: ExecutionContext) extends Sijoittelupalvelu with HakurekisteriJsonSupport {
  val logger = LoggerFactory.getLogger(getClass)
  import scala.concurrent.duration._
  implicit val httpClient = new ApacheHttpClient(socketTimeout = 120.seconds.toMillis.toInt)()
  val casClient = new CasClient(serviceAccessUrl, serviceUrl, user, password)

  def readBody[A <: AnyRef: Manifest](response: HttpResponse): A = {
    import org.json4s.jackson.Serialization.read
    val rawResult = Try(read[A](response.bodyString))

    if (rawResult.isFailure) logger.warn("Failed to deserialize", rawResult.failed.get)

    val result = rawResult.get
    result
  }

  override def getSijoitteluTila(hakuOid: String): Future[SijoitteluPagination] = {
    val url = new URL(serviceUrl + "/resources/sijoittelu/" + hakuOid + "/sijoitteluajo/latest/hakemukset")
    casClient.getProxyTicket.flatMap((ticket) => {
      logger.debug(s"calling sijoittelu-service url $url, ticket $ticket")

      GET(url).addHeaders("CasSecurityTicket" -> ticket).apply.map((response: HttpResponse) => {
      if (response.code == HttpResponseCode.Ok) {
        val sijoitteluTulos = readBody[SijoitteluPagination](response)
        logger.debug(s"got response from url $url, ticket $ticket")

        sijoitteluTulos
      } else {
        logger.error(s"call to sijoittelu-service url $url, ticket $ticket failed: ${response.code}")
        throw new RuntimeException(s"virhe kutsuttaessa sijoittelupalvelua: ${response.code}")
      }
    })})
  }
}

