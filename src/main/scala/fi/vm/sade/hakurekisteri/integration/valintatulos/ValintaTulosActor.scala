package fi.vm.sade.hakurekisteri.integration.valintatulos

import java.net.URLEncoder

import akka.actor.Actor
import akka.event.Logging
import akka.pattern.pipe
import com.stackmob.newman.response.HttpResponseCode
import fi.vm.sade.hakurekisteri.integration.{PreconditionFailedException, VirkailijaRestClient}

import scala.compat.Platform
import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.duration._

case class ValintaTulosQuery(hakuOid: String,
                             hakemusOid: String,
                             cachedOk: Boolean = true)

case class ValintaTulosHakutoive(hakukohdeOid: String,
                                 tarjoajaOid: String,
                                 valintatila: String,
                                 vastaanottotila: String,
                                 ilmoittautumistila: String,
                                 vastaanotettavuustila: String,
                                 julkaistavissa: Boolean)

case class ValintaTulos(hakemusOid: String,
                        hakutoiveet: Seq[ValintaTulosHakutoive])

case class CacheKey(hakuOid: String, hakemusOid: String)
case class CachedValintaTulos(inserted: Long, tulos: Future[ValintaTulos])

class ValintaTulosActor(restClient: VirkailijaRestClient)
                       (implicit val ec: ExecutionContext) extends Actor {

  val log = Logging(context.system, this)
  val maxRetries = 5
  var cache: Map[CacheKey, CachedValintaTulos] = Map()
  val expirationDurationMillis = 60.minutes.toMillis

  override def receive: Receive = {
    case q: ValintaTulosQuery => getTulos(q) pipeTo sender
  }

  def getTulos(q: ValintaTulosQuery): Future[ValintaTulos] = {
    val key = CacheKey(q.hakuOid, q.hakemusOid)
    if (q.cachedOk && cache.contains(key) && cache(key).inserted + expirationDurationMillis > Platform.currentTime) cache(key).tulos
    else try {
      val f = restClient.readObject[ValintaTulos](s"/haku/${URLEncoder.encode(q.hakuOid, "UTF-8")}/hakemus/${URLEncoder.encode(q.hakemusOid, "UTF-8")}", maxRetries, HttpResponseCode.Ok)
      cache = cache + (key -> CachedValintaTulos(Platform.currentTime, f))
      f
    } catch {
      case t: PreconditionFailedException =>
        log.warning(s"valinta tulos not found with haku ${q.hakuOid} and hakemus ${q.hakemusOid}: $t")
        Future.successful(ValintaTulos(q.hakemusOid, Seq()))
    }
  }
  
}
