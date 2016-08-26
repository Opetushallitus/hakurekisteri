package fi.vm.sade.hakurekisteri.integration.valintatulos

import java.util.concurrent.ExecutionException

import akka.actor.{Actor, ActorLogging, Cancellable}
import akka.pattern.pipe
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.valintatulos.Ilmoittautumistila._
import fi.vm.sade.hakurekisteri.integration.valintatulos.Valintatila._
import fi.vm.sade.hakurekisteri.integration.valintatulos.Vastaanottotila._
import fi.vm.sade.hakurekisteri.integration.{FutureCache, PreconditionFailedException, VirkailijaRestClient}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}

case class InitialLoadingNotDone() extends Exception("Initial loading noy yet done")

case class ValintaTulosQuery(hakuOid: String,
                             hakemusOid: Option[String],
                             cachedOk: Boolean = true)

class ValintaTulosActor(client: VirkailijaRestClient,
                        config: Config,
                        refetchTime: Option[Long] = None,
                        cacheTime: Option[Long] = None,
                        retryTime: Option[Long] = None,
                        initOnStartup: Boolean = false) extends Actor with ActorLogging {

  implicit val ec: ExecutionContext = context.dispatcher
  private val maxRetries: Int = config.integrations.valintaTulosConfig.httpClientMaxRetries
  private val refetch: FiniteDuration = refetchTime.map(_.milliseconds).getOrElse((config.integrations.valintatulosCacheHours / 2).hours)
  private val retry: FiniteDuration = retryTime.map(_.milliseconds).getOrElse(60.seconds)
  private val cache: FutureCache[String, SijoitteluTulos] = new FutureCache[String, SijoitteluTulos](cacheTime.getOrElse(config.integrations.valintatulosCacheHours.hours.toMillis))
  private var calling: Boolean = false
  private var initialLoadingDone = initOnStartup

  case class CacheResponse(haku: String, response: SijoitteluTulos)
  case class UpdateFailed(haku: String, t: Throwable)
  object UpdateNext

  private var updateRequestQueue: Map[String, Seq[Promise[SijoitteluTulos]]] = Map()
  private var scheduledUpdates: Map[String, Cancellable] = Map()

  override def receive: Receive = {
    case q: ValintaTulosQuery =>
      getSijoittelu(q) pipeTo sender
      self ! UpdateNext

    case BatchUpdateValintatulos(haut) =>
      haut.foreach(haku => if (!updateRequestQueue.contains(haku.haku)) updateRequestQueue = updateRequestQueue + (haku.haku -> Seq()))
      self ! UpdateNext

    case UpdateValintatulos(haku) =>
      if (!updateRequestQueue.contains(haku)) {
        updateRequestQueue = updateRequestQueue + (haku -> Seq())
      }
      self ! UpdateNext

    case UpdateNext if !calling && updateRequestQueue.isEmpty && !initialLoadingDone =>
      initialLoadingDone = true
      log.info("initial loading done")

    case UpdateNext if !calling && updateRequestQueue.nonEmpty =>
      calling = true
      val (haku, waitingRequests) = nextUpdateRequest
      val result = callBackend(haku, None)
      waitingRequests.foreach(_.tryCompleteWith(result))
      result.onFailure {
        case t =>
          log.warning(s"valinta tulos update failed for haku $haku: ${t.getMessage}")
          rescheduleHaku(haku, retry)
      }
      result
        .map(CacheResponse(haku, _))
        .recoverWith {
        case t: Throwable =>
          Future.successful(UpdateFailed(haku, t))
      } pipeTo self

    case CacheResponse(haku, tulos) =>
      cache + (haku, Future.successful(tulos))
      calling = false
      self ! UpdateNext

    case UpdateFailed(haku, t) =>
      log.error(t, s"failed to fetch sijoittelu for haku $haku")
      calling = false
      self ! UpdateNext
  }

  private def nextUpdateRequest: (String, Seq[Promise[SijoitteluTulos]]) = {
    val sortedRequests = updateRequestQueue.toList.sortBy(_._2.length)(Ordering[Int].reverse)
    val updateRequest = sortedRequests.head
    updateRequestQueue = updateRequestQueue - updateRequest._1
    updateRequest
  }

  private def getSijoittelu(q: ValintaTulosQuery): Future[SijoitteluTulos] = {
    if (!initialLoadingDone) {
      Future.failed(InitialLoadingNotDone())
    } else {
      if (q.cachedOk && cache.contains(q.hakuOid))
        cache.get(q.hakuOid)
      else {
        if (q.hakemusOid.isEmpty) {
          queueForResult(q.hakuOid)
        } else {
          callBackend(q.hakuOid, q.hakemusOid)
        }
      }
    }
  }

  private def queueForResult(haku: String): Future[SijoitteluTulos] = {
    val waitingRequest = Promise[SijoitteluTulos]()
    updateRequestQueue = updateRequestQueue + (haku -> (updateRequestQueue.getOrElse(haku, Seq()) :+ waitingRequest))
    waitingRequest.future
  }

  private def callBackend(hakuOid: String, hakemusOid: Option[String]): Future[SijoitteluTulos] = {
    def is404(t: Throwable): Boolean = t match {
      case PreconditionFailedException(_, 404) => true
      case _ => false
    }

    def getSingleHakemus(hakemusOid: String): Future[SijoitteluTulos] = client.
      readObject[ValintaTulos]("valinta-tulos-service.hakemus",hakuOid,hakemusOid)(200, maxRetries).
      recoverWith {
        case t: ExecutionException if t.getCause != null && is404(t.getCause) =>
          log.warning(s"valinta tulos not found with haku $hakuOid and hakemus $hakemusOid: $t")
          Future.successful(ValintaTulos(hakemusOid, Seq()))
      }.
      map(t => valintaTulokset2SijoitteluTulos(t))

    def getHaku(haku: String): Future[SijoitteluTulos] = client.
      readObject[Seq[ValintaTulos]]("valinta-tulos-service.haku", haku)(200).
      recoverWith {
        case t: ExecutionException if t.getCause != null && is404(t.getCause) =>
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
      override def ilmoittautumistila(hakemusOid: String, hakukohdeOid: String): Option[Ilmoittautumistila] = hakukohde(hakemusOid, hakukohdeOid).map(_.ilmoittautumistila.ilmoittautumistila)
    }

    hakemusOid match {
      case Some(oid) =>
        getSingleHakemus(oid)

      case None =>
        getHaku(hakuOid)
    }

  }

  private def rescheduleHaku(haku: String, time: FiniteDuration) {
    log.warning(s"rescheduling haku $haku in $time")
    if (scheduledUpdates.contains(haku) && !scheduledUpdates(haku).isCancelled) {
      scheduledUpdates(haku).cancel()
    }
    scheduledUpdates = scheduledUpdates + (haku -> context.system.scheduler.scheduleOnce(time, self, UpdateValintatulos(haku)))
  }

  override def postStop(): Unit = scheduledUpdates.foreach(_._2.cancel())

}

case class UpdateValintatulos(haku: String)

case class BatchUpdateValintatulos(haut: Set[UpdateValintatulos])