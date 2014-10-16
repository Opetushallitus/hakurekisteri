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
  val HYVAKSYTTY = Value("HYVAKSYTTY")
  val HARKINNANVARAISESTI_HYVAKSYTTY = Value("HARKINNANVARAISESTI_HYVAKSYTTY")
  val VARASIJALTA_HYVAKSYTTY = Value("VARASIJALTA_HYVAKSYTTY")
  val VARALLA = Value("VARALLA")
  val PERUUTETTU = Value("PERUUTETTU")
  val PERUNUT = Value("PERUNUT")
  val HYLATTY = Value("HYLATTY")
  val PERUUNTUNUT = Value("PERUUNTUNUT")
  val KESKEN = Value("KESKEN")
}

object Vastaanottotila extends Enumeration {
  type Vastaanottotila = Value
  val KESKEN = Value("KESKEN")
  val VASTAANOTTANUT = Value("VASTAANOTTANUT")
  val EI_VASTAANOTETTU_MAARA_AIKANA = Value("EI_VASTAANOTETTU_MAARA_AIKANA")
  val PERUNUT = Value("PERUNUT")
  val PERUUTETTU = Value("PERUUTETTU")
  val EHDOLLISESTI_VASTAANOTTANUT = Value("EHDOLLISESTI_VASTAANOTTANUT")
}

object Ilmoittautumistila extends Enumeration {
  type Ilmoittautumistila = Value
  val EI_TEHTY = Value("EI_TEHTY") // Ei tehty
  val LASNA_KOKO_LUKUVUOSI = Value("LASNA_KOKO_LUKUVUOSI") // Läsnä (koko lukuvuosi)
  val POISSA_KOKO_LUKUVUOSI = Value("POISSA_KOKO_LUKUVUOSI") // Poissa (koko lukuvuosi)
  val EI_ILMOITTAUTUNUT = Value("EI_ILMOITTAUTUNUT") // Ei ilmoittautunut
  val LASNA_SYKSY = Value("LASNA_SYKSY") // Läsnä syksy, poissa kevät
  val POISSA_SYKSY = Value ("POISSA_SYKSY") // Poissa syksy, läsnä kevät
  val LASNA = Value("LASNA") // Läsnä, keväällä alkava koulutus
  val POISSA = Value("POISSA") // Poissa, keväällä alkava koulutus
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
