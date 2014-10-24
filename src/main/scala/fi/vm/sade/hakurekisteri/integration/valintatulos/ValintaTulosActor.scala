package fi.vm.sade.hakurekisteri.integration.valintatulos

import java.net.URLEncoder

import akka.actor.{ActorLogging, Actor}
import akka.pattern.pipe
import fi.vm.sade.hakurekisteri.integration.valintatulos.Ilmoittautumistila._
import fi.vm.sade.hakurekisteri.integration.valintatulos.Valintatila._
import fi.vm.sade.hakurekisteri.integration.valintatulos.Vastaanottotila._
import fi.vm.sade.hakurekisteri.integration.{PreconditionFailedException, FutureCache, VirkailijaRestClient}
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.util.{Failure, Success}

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
      updateCache(haku, sijoitteluTulos(haku, None))
  }

  def updateCache(hakuOid: String, tulos: Future[SijoitteluTulos]): Unit = {
    tulos.onComplete {
      case Success(t) =>
        rescheduleHaku(hakuOid)
      case Failure(t) =>
        log.error(t, s"failed to fetch sijoittelu for haku $hakuOid")
        rescheduleHaku(hakuOid, retry)
    }
    cache + (hakuOid, tulos)
  }

  def getSijoittelu(q: ValintaTulosQuery): Future[SijoitteluTulos] = {
    if (q.cachedOk && cache.contains(q.hakuOid)) cache.get(q.hakuOid)
    else {
      val tulos = sijoitteluTulos(q.hakuOid, q.hakemusOid)
      if (q.hakemusOid.isEmpty) updateCache(q.hakuOid, tulos)
      tulos
    }
  }

  def sijoitteluTulos(hakuOid: String, hakemusOid: Option[String]): Future[SijoitteluTulos] = {

    def getSingleHakemus(hakemusOid: String): Future[SijoitteluTulos] = restClient.
      readObject[ValintaTulos](s"/haku/${URLEncoder.encode(hakuOid, "UTF-8")}/hakemus/${URLEncoder.encode(hakemusOid, "UTF-8")}", maxRetries, 200).
      recoverWith {
        case t: PreconditionFailedException if t.responseCode == 404 =>
          log.warning(s"valinta tulos not found with haku $hakuOid and hakemus $hakemusOid: $t")
          Future.successful(ValintaTulos(hakemusOid, Seq()))
      }.
      map(t => valintaTulokset2SijoitteluTulos(t))

    def getHaku(haku: String): Future[SijoitteluTulos] = restClient.
      readObject[Seq[ValintaTulos]](s"/haku/${URLEncoder.encode(haku, "UTF-8")}", maxRetries, 200).
      recoverWith {
        case t: PreconditionFailedException if t.responseCode == 404 =>
          log.warning(s"valinta tulos not found with haku $hakuOid and hakemus $hakemusOid: $t")
          Future.successful(Seq[ValintaTulos]())
      }.
      map(valintaTulokset2SijoitteluTulos)

    def valintaTulokset2SijoitteluTulos(tulokset: ValintaTulos*): SijoitteluTulos = new SijoitteluTulos {
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
}
