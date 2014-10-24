package fi.vm.sade.hakurekisteri.integration.valintatulos

import java.net.URLEncoder

import akka.actor.{ActorLogging, Actor}
import akka.pattern.pipe
import com.stackmob.newman.response.HttpResponseCode
import fi.vm.sade.hakurekisteri.integration.valintatulos.Ilmoittautumistila._
import fi.vm.sade.hakurekisteri.integration.valintatulos.Valintatila._
import fi.vm.sade.hakurekisteri.integration.valintatulos.Vastaanottotila._
import fi.vm.sade.hakurekisteri.integration.{PreconditionFailedException, FutureCache, VirkailijaRestClient}
import scala.concurrent.duration._
import scala.concurrent.Future

case class ValintaTulosQuery(hakuOid: String,
                             hakemusOid: Option[String],
                             cachedOk: Boolean = true)

class ValintaTulosActor(restClient: VirkailijaRestClient) extends Actor with ActorLogging {

  implicit val ec = context.dispatcher

  private val maxRetries = 5
  private val refetch: FiniteDuration = 1.hours
  private val retry: FiniteDuration = 60.seconds
  private val cache = new FutureCache[String, SijoitteluTulos](2.hours.toMillis)

  override def receive: Receive = {
    case q: ValintaTulosQuery =>
      getSijoittelu(q) pipeTo sender

    case Update(haku) if !cache.inUse(haku) =>
      cache - haku

    case Update(haku) =>
      val result = sijoitteluTulos(haku, None)
      result.onFailure {
        case t =>
          log.error(t, s"failed to fetch sijoittelu for haku $haku")
          rescheduleHaku(haku, retry)
      }
      result map (Sijoittelu(haku, _)) pipeTo self

    case Sijoittelu(haku, st) =>
      cache + (haku, Future.successful(st))
      rescheduleHaku(haku)
  }

  def getSijoittelu(q: ValintaTulosQuery): Future[SijoitteluTulos] = {
    if (q.cachedOk && cache.contains(q.hakuOid)) cache.get(q.hakuOid)
    else updateCacheFor(q)
  }

  def updateCacheFor(q: ValintaTulosQuery): Future[SijoitteluTulos] = {
    val tulos = sijoitteluTulos(q.hakuOid, q.hakemusOid)
    if (q.hakemusOid.isEmpty) cache + (q.hakuOid, tulos)
    tulos.onFailure {
      case t =>
        log.error(t, s"failed to fetch sijoittelu for haku ${q.hakuOid}")
        rescheduleHaku(q.hakuOid, retry)
    }
    tulos.onSuccess {
      case _ =>
        if (q.hakemusOid.isDefined) rescheduleHaku(q.hakuOid, retry)
        rescheduleHaku(q.hakuOid)
    }
    tulos
  }


  def sijoitteluTulos(hakuOid: String, hakemusOid: Option[String]): Future[SijoitteluTulos] = {

    def getSingleHakemus(hakemusOid: String): Future[SijoitteluTulos] = {
      val f = restClient.readObject[ValintaTulos](s"/haku/${URLEncoder.encode(hakuOid, "UTF-8")}/hakemus/${URLEncoder.encode(hakemusOid, "UTF-8")}", maxRetries, HttpResponseCode.Ok).recoverWith {
        case t: PreconditionFailedException if t.responseCode == HttpResponseCode.NotFound =>
          log.warning(s"valinta tulos not found with haku $hakuOid and hakemus $hakemusOid: $t")
          Future.successful(ValintaTulos(hakemusOid, Seq()))
      }.map(t => valintaTulokset2SijoitteluTulos(Seq(t)))
      f
    }

    def getHaku(haku: String): Future[SijoitteluTulos] = {
      println(s"rest client $restClient, haku $haku")
      val f = restClient.readObject[Seq[ValintaTulos]](s"/haku/${URLEncoder.encode(haku, "UTF-8")}", maxRetries, HttpResponseCode.Ok).recoverWith {
        case t: PreconditionFailedException if t.responseCode == HttpResponseCode.NotFound =>
          log.warning(s"valinta tulos not found with haku $haku: $t")
          Future.successful(Seq())
      }.map(valintaTulokset2SijoitteluTulos)
      f
    }

    def valintaTulokset2SijoitteluTulos(tulokset: Seq[ValintaTulos]): SijoitteluTulos = new SijoitteluTulos {
      val hakemukset = tulokset.groupBy(t => t.hakemusOid).mapValues(_.head)

      private def hakukohde(hakemusOid: String, hakukohdeOid: String): Option[ValintaTulosHakutoive] = hakemukset.get(hakemusOid).flatMap(_.hakutoiveet.find(_.hakukohdeOid == hakukohdeOid))

      override def pisteet(hakemusOid: String, hakukohdeOid: String): Option[BigDecimal] = hakukohde(hakemusOid, hakukohdeOid).flatMap(_.pisteet)
      override def valintatila(hakemusOid: String, hakukohdeOid: String): Option[Valintatila] = hakukohde(hakemusOid, hakukohdeOid).map(_.valintatila)
      override def vastaanottotila(hakemusOid: String, hakukohdeOid: String): Option[Vastaanottotila] = hakukohde(hakemusOid, hakukohdeOid).map(_.vastaanottotila)
      override def ilmoittautumistila(hakemusOid: String, hakukohdeOid: String): Option[Ilmoittautumistila] = hakukohde(hakemusOid, hakukohdeOid).map(_.ilmoittautumistila)
    }

    hakemusOid match {
      case Some(oid) =>
        getSingleHakemus(oid)

      case None =>
        getHaku(hakuOid)
    }

  }

  def rescheduleHaku(haku: String, time: FiniteDuration = refetch) {
    log.debug(s"rescheduling haku $haku in $time")
    context.system.scheduler.scheduleOnce(time, self, Update(haku))
  }

  case class Update(haku: String)
  case class Sijoittelu(haku: String, st: SijoitteluTulos)
}
