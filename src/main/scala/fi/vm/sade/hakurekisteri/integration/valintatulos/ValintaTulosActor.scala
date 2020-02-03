package fi.vm.sade.hakurekisteri.integration.valintatulos

import java.util.concurrent.ExecutionException

import akka.actor.{Actor, ActorLogging, ActorRef, Status}
import akka.pattern.{ask, pipe}
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.cache.CacheFactory
import fi.vm.sade.hakurekisteri.integration.haku.{AllHaut, HakuRequest}
import fi.vm.sade.hakurekisteri.integration.{ExecutorUtil, PreconditionFailedException, VirkailijaRestClient}
import support.TypedActorRef

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class HakemuksenValintatulos(hakuOid: String, hakemusOid: String)
case class HaunValintatulos(hakuOid: String)

class ValintaTulosActor(hautActor: ActorRef,
                        client: VirkailijaRestClient,
                        config: Config,
                        cacheFactory: CacheFactory,
                        cacheTime: Option[Long] = None) extends Actor with ActorLogging {

  implicit private val timeout: akka.util.Timeout = config.integrations.valintaTulosConfig.httpClientRequestTimeout.milliseconds
  implicit private val ec: ExecutionContext = ExecutorUtil.createExecutor(config.integrations.asyncOperationThreadPoolSize, getClass.getSimpleName)
  private val maxRetries = config.integrations.valintaTulosConfig.httpClientMaxRetries
  private val cache = cacheFactory.getInstance[String, SijoitteluTulos](cacheTime.getOrElse(config.integrations.valintatulosCacheHours.hours.toMillis), this.getClass, classOf[SijoitteluTulos], "sijoittelu-tulos")
  private val cacheRefreshInterval = config.integrations.valintatulosRefreshTimeHours.hours
  private val cacheRefreshScheduler = context.system.scheduler.schedule(1.second, cacheRefreshInterval, hautActor, HakuRequest)

  override def receive: Receive = withQueue(Map.empty)

  override def postStop(): Unit = {
    cacheRefreshScheduler.cancel()
    super.postStop()
  }

  private def withQueue(haunValintatulosFetchQueue: Map[String, Vector[ActorRef]]): Receive = {
      case HakemuksenValintatulos(hakuOid, hakemusOid) =>
        hakemuksenTulos(hakuOid, hakemusOid) pipeTo sender

      case HaunValintatulos(hakuOid) =>
        cache.get(hakuOid, hakuOid => { (self ? FetchHaunValintatulos(hakuOid)).mapTo[SijoitteluTulos].map(Some(_)) }).map(_.get) pipeTo sender

      case FetchHaunValintatulos(hakuOid) =>
        if (haunValintatulosFetchQueue.isEmpty) {
          haunTulos(hakuOid).onComplete(self ! FetchedHaunValintatulos(hakuOid, _))
        }
        context.become(withQueue(haunValintatulosFetchQueue + (hakuOid -> (haunValintatulosFetchQueue.getOrElse(hakuOid, Vector.empty) :+ sender))))

      case FetchedHaunValintatulos(hakuOid, result) =>
        result match {
          case Success(tulos) =>
            haunValintatulosFetchQueue.get(hakuOid).foreach(_.foreach(_ ! tulos))
          case Failure(t) =>
            haunValintatulosFetchQueue.get(hakuOid).foreach(_.foreach(_ ! Status.Failure(t)))
        }
        val newQueue = haunValintatulosFetchQueue - hakuOid
        if (newQueue.nonEmpty) {
          val nextHakuOid = newQueue.maxBy(_._2.length)._1
          haunTulos(nextHakuOid).onComplete(self ! FetchedHaunValintatulos(nextHakuOid, _))
        }
        context.become(withQueue(newQueue))

      case AllHaut(haut) =>
        haut.filter(_.isActive).foreach(haku => self ! FetchHaunValintatulos(haku.oid))

      case tulos: SijoitteluTulos =>
        log.info("Caching haun tulos for haku {} with {} jonotietos", tulos.hakuOid, tulos.valintatapajono.size)
        cache + (tulos.hakuOid, tulos)

      case Status.Failure(t) =>
        log.error(t, "Failed to update valintatulos")
  }

  private def is404(t: Throwable): Boolean = t match {
    case PreconditionFailedException(_, 404) => true
    case _ => false
  }

  private def hakemuksenTulos(hakuOid: String, hakemusOid: String): Future[SijoitteluTulos] = {
    client
      .readObject[ValintaTulos]("valinta-tulos-service.hakemus", hakuOid, hakemusOid)(200, maxRetries)
      .recover {
        case t: ExecutionException if is404(t.getCause) =>
          log.warning(s"valinta tulos not found with haku $hakuOid and hakemus $hakemusOid: $t")
          ValintaTulos(hakemusOid, Seq())
      }
      .map(SijoitteluTulos(hakuOid, _))
  }

  private def haunTulos(hakuOid: String): Future[SijoitteluTulos] = {
    client
      .readObject[Seq[ValintaTulos]]("valinta-tulos-service.haku", hakuOid)(200, maxRetries)
      .recover {
        case t: ExecutionException if t.getCause != null && is404(t.getCause) =>
          log.warning(s"valinta tulos not found with haku $hakuOid: $t")
          Seq[ValintaTulos]()
      }
      .map(SijoitteluTulos(hakuOid, _))
  }
  
  private case class FetchHaunValintatulos(hakuOid: String)
  private case class FetchedHaunValintatulos(hakuOid: String, tulos: Try[SijoitteluTulos])
}

case class ValintaTulosActorRef(actor: ActorRef) extends TypedActorRef
