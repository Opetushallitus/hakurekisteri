package fi.vm.sade.hakurekisteri.integration.valintatulos

import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ExecutionException
import akka.actor.{Actor, ActorLogging, ActorRef, Status}
import akka.event.Logging
import akka.pattern.{ask, pipe}
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.cache.{CacheFactory, RedisCache}
import fi.vm.sade.hakurekisteri.integration.haku.{AllHaut, HakuRequest}
import fi.vm.sade.hakurekisteri.integration.{
  ExecutorUtil,
  PreconditionFailedException,
  VirkailijaRestClient
}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import org.json4s.jackson.JsonMethods
import org.json4s.jackson.Serialization.write
import support.TypedActorRef

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class VirkailijanValintatulos(hakemusOid: Set[String])
case class HakemuksenValintatulos(hakuOid: String, hakemusOid: String)
case class HaunValintatulos(hakuOid: String)

class ValintaTulosActor(
  hautActor: ActorRef,
  client: VirkailijaRestClient,
  config: Config,
  cacheFactory: CacheFactory,
  cacheTime: Option[Long] = None
) extends Actor
    with ActorLogging {

  implicit private val timeout: akka.util.Timeout =
    config.integrations.valintaTulosConfig.httpClientRequestTimeout.milliseconds
  implicit private val ec: ExecutionContext = ExecutorUtil.createExecutor(
    config.integrations.asyncOperationThreadPoolSize,
    getClass.getSimpleName
  )
  private val maxRetries = config.integrations.valintaTulosConfig.httpClientMaxRetries
  private val cache = cacheFactory.getInstance[String, SijoitteluTulos](
    cacheTime.getOrElse(config.integrations.valintatulosCacheHours.hours.toMillis),
    this.getClass,
    classOf[SijoitteluTulos],
    "sijoittelu-tulos"
  )
  private val valintaCache = cacheFactory.getInstance[String, String](
    cacheTime.getOrElse(config.integrations.valpasValintatulosRefreshTimeHours.hours.toMillis),
    this.getClass,
    classOf[String],
    "valpas-valintatulos"
  )
  private val cacheRefreshInterval = config.integrations.valintatulosRefreshTimeHours.hours
  private val cacheRefreshScheduler =
    context.system.scheduler.schedule(1.second, cacheRefreshInterval, hautActor, HakuRequest)

  override def receive: Receive = withQueue(Map.empty)

  override def postStop(): Unit = {
    cacheRefreshScheduler.cancel()
    super.postStop()
  }

  implicit val formats = HakurekisteriJsonSupport.format

  private def withQueue(haunValintatulosFetchQueue: Map[String, Vector[ActorRef]]): Receive = {
    case VirkailijanValintatulos(hakemusOids) =>
      hakemuksenTulosCached(hakemusOids) pipeTo sender

    case HakemuksenValintatulos(hakuOid, hakemusOid) =>
      hakemuksenTulos(hakuOid, hakemusOid).map(SijoitteluTulos(hakuOid, _)) pipeTo sender

    case HaunValintatulos(hakuOid) =>
      cache
        .get(
          hakuOid,
          hakuOid => { (self ? FetchHaunValintatulos(hakuOid)).mapTo[SijoitteluTulos].map(Some(_)) }
        )
        .map(_.get) pipeTo sender

    case FetchHaunValintatulos(hakuOid) =>
      if (haunValintatulosFetchQueue.isEmpty) {
        haunTulos(hakuOid).onComplete(self ! FetchedHaunValintatulos(hakuOid, _))
      }
      context.become(
        withQueue(
          haunValintatulosFetchQueue + (hakuOid -> (haunValintatulosFetchQueue
            .getOrElse(hakuOid, Vector.empty) :+ sender))
        )
      )

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
      cache + (tulos.hakuOid, tulos)

    case Status.Failure(t) =>
      log.error(t, "Failed to update valintatulos")

    case StoreValintatulokset(valintatulokset) =>
      valintatulokset match {
        case Success(tulokset) =>
          tulokset.foreach { tulos =>
            valintaCache + (tulos.hakemusOid, write(tulos))
          }
        case Failure(exception) =>
          log.error(
            s"Couldn't store Valintatulokset to Redis Cache as fetching failed: ${exception.getMessage}",
            exception
          )
      }
  }

  private def is404(t: Throwable): Boolean = {
    def _is404(t2: Throwable): Boolean = t2 match {
      case PreconditionFailedException(_, 404) => true
      case _                                   => false
    }
    t match {
      case t: InvocationTargetException => _is404(t.getCause)
      case _                            => _is404(t)
    }
  }

  private def hakemuksenTulos(hakuOid: String, hakemusOid: String): Future[ValintaTulos] = {
    client
      .readObject[ValintaTulos]("valinta-tulos-service.hakemus", hakuOid, hakemusOid)(
        200,
        maxRetries
      )
      .recover {
        case t: ExecutionException if is404(t.getCause) =>
          log.warning(s"valinta tulos not found with haku $hakuOid and hakemus $hakemusOid: $t")
          ValintaTulos(hakemusOid, Seq())
      }
  }
  private def parseToValintaTulosOrEvict(hakemusOid: String, json: String): Option[ValintaTulos] = {
    Try(JsonMethods.parse(json).extract[ValintaTulos]) match {
      case Success(vt) =>
        Some(vt)
      case Failure(ex) =>
        log.error(s"Failed to parse Valintatulos: ${ex.getMessage}", ex)
        Try(valintaCache.-(hakemusOid))
        None
    }
  }
  private def parseToValintaTulos(
    v: Future[Seq[(Option[String], String)]]
  ): Future[Seq[Option[ValintaTulos]]] = {
    v.map(vv =>
      vv.map {
        case (Some(o), s) =>
          parseToValintaTulosOrEvict(s, o)
        case _ =>
          None
      }
    )
  }

  private def cachedValintatulokset(hakemusOids: Set[String]): Future[Seq[ValintaTulos]] = {
    valintaCache match {
      case redis: RedisCache[String, String] =>
        val oidsInSeq: Seq[String] = hakemusOids.toSeq
        val v: Future[Seq[(Option[String], String)]] = redis.mget(oidsInSeq)

        parseToValintaTulos(v).map(_.flatten)
      case _ =>
        Future.successful(Seq.empty)
    }
  }

  private def hakemuksenTulosCached(hakemusOids: Set[String]): Future[Seq[ValintaTulos]] = {
    def fetchForReal(oids: Set[String]) = client
      .postObject[Set[String], List[ValintaTulos]]("valinta-tulos-service.hakemukset")(
        200,
        oids
      )

    for {
      valintatulosCached: Seq[ValintaTulos] <- cachedValintatulokset(hakemusOids)
      allValintatulokset <- hakemusOids.diff(
        valintatulosCached.map(_.hakemusOid).toSet
      ) match {
        case s if s.isEmpty => Future.successful(valintatulosCached)
        case s =>
          val restFetchedValintatulokset: Future[List[ValintaTulos]] = fetchForReal(s)
          restFetchedValintatulokset.onComplete { tulokset =>
            self ! StoreValintatulokset(tulokset)
          }
          restFetchedValintatulokset.map(_.++(valintatulosCached))
      }
    } yield allValintatulokset
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
