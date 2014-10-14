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
  val hyväksytty = Value("HYVAKSYTTY")
  val harkinnanvaraisesti_hyväksytty = Value("HARKINNANVARAISESTI_HYVAKSYTTY")
  val varasijalta_hyväksytty = Value("VARASIJALTA_HYVAKSYTTY")
  val varalla = Value("VARALLA")
  val peruutettu = Value("PERUUTETTU")
  val perunut = Value("PERUNUT")
  val hylätty = Value("HYLATTY")
  val peruuntunut = Value("PERUUNTUNUT")
  val kesken = Value("KESKEN")
}

object Vastaanottotila extends Enumeration {
  type Vastaanottotila = Value
  val kesken = Value("KESKEN")
  val vastaanottanut = Value("VASTAANOTTANUT")
  val ei_vastaanotetu_määräaikana = Value("EI_VASTAANOTETTU_MAARA_AIKANA")
  val perunut = Value("PERUNUT")
  val peruutettu = Value("PERUUTETTU")
  val ehdollisesti_vastaanottanut = Value("EHDOLLISESTI_VASTAANOTTANUT")
}

object Ilmoittautumistila extends Enumeration {
  type Ilmoittautumistila = Value
  val ei_tehty = Value("EI_TEHTY") // Ei tehty
  val läsnä_koko_lukuvuosi = Value("LASNA_KOKO_LUKUVUOSI") // Läsnä (koko lukuvuosi)
  val poissa_koko_lukuvuosi = Value("POISSA_KOKO_LUKUVUOSI") // Poissa (koko lukuvuosi)
  val ei_ilmoittautunut = Value("EI_ILMOITTAUTUNUT") // Ei ilmoittautunut
  val läsnä_syksy = Value("LASNA_SYKSY") // Läsnä syksy, poissa kevät
  val poissa_syksy = Value ("POISSA_SYKSY") // Poissa syksy, läsnä kevät
  val läsnä = Value("LASNA") // Läsnä, keväällä alkava koulutus
  val poissa = Value("POISSA") // Poissa, keväällä alkava koulutus
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
