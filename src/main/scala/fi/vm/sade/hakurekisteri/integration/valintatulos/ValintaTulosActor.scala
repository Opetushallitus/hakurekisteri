package fi.vm.sade.hakurekisteri.integration.valintatulos

import java.net.URLEncoder

import akka.actor.Actor
import akka.event.Logging
import akka.pattern.pipe
import com.stackmob.newman.response.HttpResponseCode
import fi.vm.sade.hakurekisteri.integration.{FutureCache, PreconditionFailedException, VirkailijaRestClient}
import fi.vm.sade.hakurekisteri.integration.valintatulos.Ilmoittautumistila.Ilmoittautumistila
import fi.vm.sade.hakurekisteri.integration.valintatulos.Valintatila.Valintatila
import fi.vm.sade.hakurekisteri.integration.valintatulos.Vastaanottotila.Vastaanottotila

import scala.concurrent.{Future, ExecutionContext}

object Valintatila extends Enumeration {
  type Valintatila = Value
  val HYVAKSYTTY, HARKINNANVARAISESTI_HYVAKSYTTY, VARASIJALTA_HYVAKSYTTY, VARALLA, PERUUTETTU, PERUNUT, HYLATTY, PERUUNTUNUT, KESKEN = Value
}

object Vastaanottotila extends Enumeration {
  type Vastaanottotila = Value
  val KESKEN, VASTAANOTTANUT, EI_VASTAANOTETTU_MAARA_AIKANA, PERUNUT, PERUUTETTU, EHDOLLISESTI_VASTAANOTTANUT = Value
}

object Ilmoittautumistila extends Enumeration {
  type Ilmoittautumistila = Value
  val EI_TEHTY, LASNA_KOKO_LUKUVUOSI, POISSA_KOKO_LUKUVUOSI, EI_ILMOITTAUTUNUT, LASNA_SYKSY, POISSA_SYKSY, LASNA, POISSA = Value
}

case class ValintaTulosQuery(hakuOid: String,
                             hakemusOid: String,
                             cachedOk: Boolean = true)

case class ValintaTulosHakutoive(hakukohdeOid: String,
                                 tarjoajaOid: String,
                                 valintatila: Valintatila,
                                 vastaanottotila: Vastaanottotila,
                                 ilmoittautumistila: Ilmoittautumistila,
                                 vastaanotettavuustila: String,
                                 julkaistavissa: Boolean)

case class ValintaTulos(hakemusOid: String,
                        hakutoiveet: Seq[ValintaTulosHakutoive])

case class CacheKey(hakuOid: String, hakemusOid: String)

class ValintaTulosActor(restClient: VirkailijaRestClient)
                       (implicit val ec: ExecutionContext) extends Actor {

  val log = Logging(context.system, this)
  val maxRetries = 5
  private val cache = new FutureCache[CacheKey, ValintaTulos]()

  override def receive: Receive = {
    case q: ValintaTulosQuery => getTulos(q) pipeTo sender
  }

  def getTulos(q: ValintaTulosQuery): Future[ValintaTulos] = {
    val key = CacheKey(q.hakuOid, q.hakemusOid)
    if (q.cachedOk && cache.contains(key)) cache.get(key)
    else {
      val f = restClient.readObject[ValintaTulos](s"/haku/${URLEncoder.encode(q.hakuOid, "UTF-8")}/hakemus/${URLEncoder.encode(q.hakemusOid, "UTF-8")}", maxRetries, HttpResponseCode.Ok).recoverWith {
        case t: PreconditionFailedException if t.responseCode == HttpResponseCode.NotFound =>
          log.warning(s"valinta tulos not found with haku ${q.hakuOid} and hakemus ${q.hakemusOid}: $t")
          Future.successful(ValintaTulos(q.hakemusOid, Seq()))
      }
      cache + (key, f)
      f
    }
  }
  
}
