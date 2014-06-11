package fi.vm.sade.hakurekisteri.hakija

import scala.concurrent.{ExecutionContext, Future}
import org.slf4j.LoggerFactory
import com.stackmob.newman.ApacheHttpClient
import java.net.{URLEncoder, URL}
import com.stackmob.newman.dsl._
import com.stackmob.newman.response.HttpResponseCode
import akka.actor.Actor
import scala.compat.Platform

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


class SijoitteluActor(cachedService: Sijoittelupalvelu) extends Actor {


  import akka.pattern._

  import scala.concurrent.duration._

  implicit val ec = context.dispatcher

  val retry: FiniteDuration = 10.seconds


  var cache = Map[String, Future[SijoitteluTulos]]()
  var cacheHistory = Map[String, Long]()
  val expiration = 1.hour
  private val refetch: FiniteDuration = 30.minutes

  override def receive: Receive = {
    case SijoitteluQuery(haku) =>
      getSijoittelu(haku) pipeTo sender
    case Update(haku) if !inUse(haku) =>
      cache - haku
    case Update(haku) =>
      val result = sijoitteluTulos(haku)
      result.onFailure{ case t => rescheduleHaku(haku, retry)}
      result pipeTo self
    case Sijoittelu(haku, sp) =>
      cache + (haku -> Future.successful(sp))
      rescheduleHaku(haku)
  }
  def getValintatapaMap[A](shakijas: Seq[SijoitteluHakija], extractor: (SijoitteluHakutoiveenValintatapajono) => Option[A]): Map[String, Map[String, A]] = shakijas.groupBy(_.hakemusOid).
    collect {
    case (Some(hakemusOid), sijoitteluHakijas) =>


      def getIndex(toive: SijoitteluHakutoive): Option[(String, A)] = (toive.hakukohdeOid, toive.hakutoiveenValintatapajonot.flatMap(_.headOption))  match {
        case (Some(hakukohde), Some(vtjono)) => extractor(vtjono).map((hakukohde, _))
        case _ => None

      }

      (hakemusOid, (for (hakija <- sijoitteluHakijas;
                         toive <- hakija.hakutoiveet.getOrElse(Seq())) yield getIndex(toive)).flatten.toMap)
  }

  def inUse(haku: String):Boolean = cacheHistory.getOrElse(haku,0L) > (Platform.currentTime - expiration.toMillis)

  def getSijoittelu(haku:String):  Future[SijoitteluTulos] = {
    cacheHistory = cacheHistory + (haku -> Platform.currentTime)
    cache.get(haku) match {
      case Some(s)  =>
        s
      case None =>
        updateCacheFor(haku)
    }

  }


  def updateCacheFor(haku: String): Future[SijoitteluTulos] = {
    val result: Future[SijoitteluTulos] = sijoitteluTulos(haku)
    cache = cache + (haku -> result)
    rescheduleHaku(haku)
    result.onFailure{ case t => rescheduleHaku(haku, retry)}
    result
  }


  def sijoitteluTulos(haku: String) = {
    cachedService.getSijoitteluTila(haku).map(
      _.flatMap(_.results).getOrElse(Seq()))
      .map((shs: Seq[SijoitteluHakija]) => SijoitteluTulos(getValintatapaMap(shs, _.tila), getValintatapaMap(shs, _.pisteet)))
  }

  def rescheduleHaku(haku: String, time: FiniteDuration = refetch) {
    context.system.scheduler.scheduleOnce(time, self, Update(haku))
  }

  case class Update(haku:String)
  case class Sijoittelu(haku: String, sp: Option[SijoitteluPagination])

}

case class SijoitteluQuery(hakuOid: String)

class RestSijoittelupalvelu(serviceAccessUrl: String, serviceUrl: String = "https://itest-virkailija.oph.ware.fi/sijoittelu-service", user: Option[String], password: Option[String])(implicit val ec: ExecutionContext) extends Sijoittelupalvelu {
  val logger = LoggerFactory.getLogger(getClass)
  import scala.concurrent.duration._
  implicit val httpClient = new ApacheHttpClient(socketTimeout = 60.seconds.toMillis.toInt)()


  def getProxyTicket: Future[String] = (user, password) match {
    case (Some(u), Some(p)) =>
      POST(new URL(s"$serviceAccessUrl/accessTicket")).
        addHeaders("Content-Type" -> "application/x-www-form-urlencoded").
        setBodyString(s"client_id=${URLEncoder.encode(u, "UTF8")}&client_secret=${URLEncoder.encode(p, "UTF8")}&service_url=${URLEncoder.encode(serviceUrl, "UTF8")}").
        apply.map((response) => response.bodyString)
    case _ => Future.successful("")
  }

  override def getSijoitteluTila(hakuOid: String): Future[Option[SijoitteluPagination]] = {
    val url = new URL(serviceUrl + "/resources/sijoittelu/" + hakuOid + "/sijoitteluajo/latest/hakemukset")
    getProxyTicket.flatMap((ticket) => {
      logger.debug("calling sijoittelu-service [url={}, ticket={}]", Array(url, ticket))
      GET(url).addHeaders("CasSecurityTicket" -> ticket).apply.map(response => {
      if (response.code == HttpResponseCode.Ok) {
        val sijoitteluTulos = response.bodyAsCaseClass[SijoitteluPagination].toOption
        logger.debug("got response from [url={}, ticket={}]", Array(url, ticket))

        sijoitteluTulos
      } else {
        logger.error("call to sijoittelu-service [url={}, ticket={}] failed: {}", url, ticket, response.code)
        throw new RuntimeException("virhe kutsuttaessa sijoittelupalvelua: %s".format(response.code))
      }
    })})
  }

}

