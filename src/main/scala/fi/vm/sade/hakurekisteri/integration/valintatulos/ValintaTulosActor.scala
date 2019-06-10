package fi.vm.sade.hakurekisteri.integration.valintatulos

import java.util.concurrent.ExecutionException

import akka.actor.{Actor, ActorLogging, ActorRef, Status}
import akka.pattern.{ask, pipe}
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.cache.CacheFactory
import fi.vm.sade.hakurekisteri.integration.{ExecutorUtil, PreconditionFailedException, VirkailijaRestClient}
import support.TypedActorRef

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class ValintaTulosQuery(hakuOid: String, hakemusOid: Option[String])
case class UpdateValintatulos(haku: String, isRetry: Boolean = false)
case class FetchValintatulos(haku: String)
case class FetchValintatulosResult(haku: String, result: Try[SijoitteluTulos])

class ValintaTulosActor(client: VirkailijaRestClient,
                        config: Config,
                        cacheFactory: CacheFactory,
                        cacheTime: Option[Long] = None,
                        retryTime: Option[Long] = None) extends Actor with ActorLogging {

  implicit val timeout: akka.util.Timeout = config.integrations.valintaTulosConfig.httpClientRequestTimeout.milliseconds
  implicit val ec: ExecutionContext = ExecutorUtil.createExecutor(config.integrations.asyncOperationThreadPoolSize, getClass.getSimpleName)
  private val maxRetries: Int = config.integrations.valintaTulosConfig.httpClientMaxRetries
  private val retry: FiniteDuration = retryTime.map(_.milliseconds).getOrElse(60.seconds)
  private val cache = cacheFactory.getInstance[String, SijoitteluTulos](cacheTime.getOrElse(config.integrations.valintatulosCacheHours.hours.toMillis), this.getClass, classOf[SijoitteluTulos], "sijoittelu-tulos")

  override def receive: Receive = updateRequestQueue(Map.empty)

  private def updateRequestQueue(requestQueue: Map[String, Vector[ActorRef]]): Receive = {
      case ValintaTulosQuery(hakuOid, Some(hakemusOid)) =>
        hakemuksenTulos(hakuOid, hakemusOid) pipeTo sender

      case ValintaTulosQuery(hakuOid, None) =>
        cache.get(hakuOid, hakuOid => { (self ? FetchValintatulos(hakuOid)).mapTo[SijoitteluTulos].map(Some(_)) }).map(_.get) pipeTo sender

      case UpdateValintatulos(hakuOid, isRetry) =>
        (self ? FetchValintatulos(hakuOid)).mapTo[SijoitteluTulos]
          .flatMap(cache + (hakuOid, _))
          .failed
          .foreach(t => {
            if (isRetry) {
              log.error(t, s"Failed to update valintatulos of haku $hakuOid")
            } else {
              log.warning(s"Failed to update valintatulos of haku $hakuOid, retrying in $retry")
              context.system.scheduler.scheduleOnce(retry, self, UpdateValintatulos(hakuOid, true))
            }
          })

      case FetchValintatulos(hakuOid) =>
        if (requestQueue.isEmpty) {
          haunTulos(hakuOid).onComplete(self ! FetchValintatulosResult(hakuOid, _))
        }
        context.become(updateRequestQueue(requestQueue + (hakuOid -> (requestQueue.getOrElse(hakuOid, Vector.empty) :+ sender))))

      case FetchValintatulosResult(hakuOid, result) =>
        result match {
          case Success(tulos) =>
            requestQueue.get(hakuOid).foreach(_.foreach(_ ! tulos))
          case Failure(t) =>
            requestQueue.get(hakuOid).foreach(_.foreach(_ ! Status.Failure(t)))
        }
        val newQueue = requestQueue - hakuOid
        if (newQueue.nonEmpty) {
          val nextHakuOid = newQueue.maxBy(_._2.length)._1
          haunTulos(nextHakuOid).onComplete(self ! FetchValintatulosResult(nextHakuOid, _))
        }
        context.become(updateRequestQueue(newQueue))
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
      .map(t => ValintaTulosToSijoitteluTulos(Map(t.hakemusOid -> t)))
  }

  private def haunTulos(hakuOid: String): Future[SijoitteluTulos] = {
    client
      .readObject[Seq[ValintaTulos]]("valinta-tulos-service.haku", hakuOid)(200)
      .recover {
        case t: ExecutionException if t.getCause != null && is404(t.getCause) =>
          log.warning(s"valinta tulos not found with haku $hakuOid: $t")
          Seq[ValintaTulos]()
      }
      .map(ts =>  ValintaTulosToSijoitteluTulos(ts.map(t => t.hakemusOid -> t).toMap))
  }
}

case class ValintaTulosActorRef(actor: ActorRef) extends TypedActorRef
