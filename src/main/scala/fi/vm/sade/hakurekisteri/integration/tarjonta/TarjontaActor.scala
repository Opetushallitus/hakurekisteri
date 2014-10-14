package fi.vm.sade.hakurekisteri.integration.tarjonta

import java.net.URLEncoder

import akka.actor.Actor
import com.stackmob.newman.response.HttpResponseCode
import fi.vm.sade.hakurekisteri.integration.{FutureCache, VirkailijaRestClient}

import scala.compat.Platform
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import akka.pattern.pipe
import org.scalatra.util.RicherString._

case class SearchKomoQuery(koulutus: String)

case class GetKomoQuery(oid: String)

object GetHautQuery

case class RestHakuResult(result: List[RestHaku])

case class RestHaku(oid:Option[String], hakuaikas: List[RestHakuAika], nimi: Map[String, String], hakukausiUri: String,
hakukausiVuosi: Int, kohdejoukkoUri: Option[String])

case class RestHakuAika(alkuPvm:Long)

case class TarjontaSearchResponse(result: Seq[Komo])

case class TarjontaKomoResponse(result: Option[Komo])

case class KomoResponse(oid: String, komo: Option[Komo])

case class Koulutus(oid: String, komoOid: String, tunniste: Option[String])
case class KoulutusResponse(result: Option[Koulutus])

case class HakukohdeOid(oid: String)
case class Hakukohde(oid: String, hakukohdeKoulutusOids: Seq[String], ulkoinenTunniste: Option[String])
case class HakukohdeResponse(result: Option[Hakukohde])

case class Hakukohteenkoulutus(komoOid: String,
                               tkKoulutuskoodi: String,
                               kkKoulutusId: Option[String])
case class HakukohteenKoulutukset(hakukohdeOid: String, ulkoinenTunniste: Option[String], koulutukset: Seq[Hakukohteenkoulutus])

class TarjontaException(val m: String) extends Exception(m)
case class HakukohdeNotFoundException(message: String) extends TarjontaException(message)
case class KoulutusNotFoundException(message: String) extends TarjontaException(message)
case class KomoNotFoundException(message: String) extends TarjontaException(message)

class TarjontaActor(restClient: VirkailijaRestClient)(implicit val ec: ExecutionContext) extends Actor {
  private val koulutusCache = new FutureCache[String, HakukohteenKoulutukset]()
  private val komoCache = new FutureCache[String, KomoResponse]()
  val maxRetries = 5

  override def receive: Receive = {
    case q: SearchKomoQuery => searchKomo(q.koulutus) pipeTo sender
    case q: GetKomoQuery => getKomo(q.oid) pipeTo sender
    case GetHautQuery => getHaut pipeTo sender
    case oid: HakukohdeOid => getHakukohteenKoulutukset(oid) pipeTo sender
  }
  
  def searchKomo(koulutus: String): Future[Seq[Komo]] = {
    restClient.readObject[TarjontaSearchResponse](s"/rest/v1/komo/search?koulutus=${URLEncoder.encode(koulutus, "UTF-8")}", maxRetries, HttpResponseCode.Ok).map(_.result)
  }

  def getKomo(oid: String): Future[KomoResponse] = {
    if (komoCache.contains(oid)) komoCache.get(oid)
    else {
      val f = restClient.readObject[TarjontaKomoResponse](s"/rest/v1/komo/${URLEncoder.encode(oid, "UTF-8")}?meta=false", maxRetries, HttpResponseCode.Ok).map(res => KomoResponse(oid, res.result))
      komoCache + (oid, f)
      f
    }
  }

  def getHaut: Future[RestHakuResult] = restClient.readObject[RestHakuResult]("/rest/v1/haku/findAll", HttpResponseCode.Ok)

  def getKoulutus(oid: String): Future[Hakukohteenkoulutus] = {
    val koulutus: Future[Option[Koulutus]] = restClient.readObject[KoulutusResponse](s"/rest/v1/koulutus/${URLEncoder.encode(oid, "UTF-8")}?meta=false", maxRetries, HttpResponseCode.Ok).map(r => r.result)
    koulutus.flatMap {
      case None => Future.failed(KoulutusNotFoundException(s"koulutus not found with oid $oid"))
      case Some(k) =>
        val fk: Future[Option[Komo]] = getKomo(k.komoOid).map(r => r.komo)
        fk.map {
          case None => throw KomoNotFoundException(s"komo not found with oid ${k.komoOid}")
          case Some(komo) =>
            Hakukohteenkoulutus(komo.oid, komo.koulutuskoodi.arvo, k.tunniste.flatMap(_.blankOption))
        }
    }
  }
  def getHakukohteenkoulutukset(oids: Seq[String]): Future[Seq[Hakukohteenkoulutus]] = Future.sequence(oids.map(getKoulutus))

  def getHakukohteenKoulutukset(hk: HakukohdeOid): Future[HakukohteenKoulutukset] = {
    if (koulutusCache.contains(hk.oid)) koulutusCache.get(hk.oid)
    else {
      val fh: Future[Option[Hakukohde]] = restClient.readObject[HakukohdeResponse](s"/rest/v1/hakukohde/${URLEncoder.encode(hk.oid, "UTF-8")}?meta=false", maxRetries, HttpResponseCode.Ok).map(r => r.result)
      val hks: Future[HakukohteenKoulutukset] = fh.flatMap {
        case None => Future.failed(HakukohdeNotFoundException(s"hakukohde not found with oid ${hk.oid}"))
        case Some(h) => for (
          hakukohteenkoulutukset: Seq[Hakukohteenkoulutus] <- getHakukohteenkoulutukset(h.hakukohdeKoulutusOids)
        ) yield HakukohteenKoulutukset(h.oid, h.ulkoinenTunniste, hakukohteenkoulutukset)
      }
      koulutusCache + (hk.oid, hks)
      hks
    }
  }
}


