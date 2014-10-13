package fi.vm.sade.hakurekisteri.ensikertalainen


import akka.actor.{Actor, ActorRef, Props}
import akka.event.Logging
import akka.pattern.pipe
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.integration.FutureCache
import fi.vm.sade.hakurekisteri.integration.hakemus.Trigger
import fi.vm.sade.hakurekisteri.integration.henkilo.HenkiloResponse
import fi.vm.sade.hakurekisteri.integration.tarjonta.{GetKomoQuery, Komo, KomoResponse, Koulutuskoodi}
import fi.vm.sade.hakurekisteri.integration.virta.{VirtaData, VirtaQuery}
import fi.vm.sade.hakurekisteri.opiskeluoikeus.{Opiskeluoikeus, OpiskeluoikeusQuery}
import fi.vm.sade.hakurekisteri.rest.support.Query
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, SuoritusQuery, VapaamuotoinenSuoritus, VirallinenSuoritus}
import org.joda.time.{DateTime, LocalDate}

import scala.collection.immutable.Iterable
import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

case class EnsikertalainenQuery(henkiloOid: String, hetu: Option[String]= None)

object QueryCount

case class QueriesRunning(count: Map[String, Int], timestamp: Long = Platform.currentTime)

case class CacheResult(q: EnsikertalainenQuery, f: Future[Ensikertalainen])

class EnsikertalainenActor(suoritusActor: ActorRef, opiskeluoikeusActor: ActorRef, virtaActor: ActorRef, henkiloActor: ActorRef, tarjontaActor: ActorRef, hakemukset : ActorRef)(implicit val ec: ExecutionContext) extends Actor {
  val logger = Logging(context.system, this)
  val kesa2014: DateTime = new LocalDate(2014, 7, 1).toDateTimeAtStartOfDay
  implicit val defaultTimeout: Timeout = 30.seconds
  val cache = new FutureCache[EnsikertalainenQuery, Ensikertalainen](24.hours.toMillis)

  override def receive: Receive = {
    case q: EnsikertalainenQuery =>
      if (cache.contains(q)) cache.get(q) pipeTo sender
      else {
        logger.debug(s"EnsikertalainenQuery(${q.henkiloOid}) with ${q.hetu.map("hetu: " + _).getOrElse("no hetu")}")
        context.actorOf(Props(new EnsikertalaisuusCheck())).forward(q)
      }

    case CacheResult(q, f) => cache + (q, f)

    case QueryCount =>
      import akka.pattern.ask
      implicit val ec = context.dispatcher
      val statusRequests: Iterable[Future[String]] = for (
        query: ActorRef <- context.children
      ) yield (query ? ReportStatus)(5.seconds).mapTo[QueryStatus].map(_.status).recover{case _ => "status query failed"}

      val statuses = Future.sequence(statusRequests)

      val counts: Future[Map[String, Int]] = statuses.
        map(_.groupBy(i => i).
        map((t) => (t._1, t._2.toList.length)))
      counts.map(QueriesRunning(_)) pipeTo sender
  }

  class EnsikertalaisuusCheck() extends Actor {
    var suoritukset: Option[Seq[Suoritus]] = None

    var opiskeluOikeudet: Option[Seq[Opiskeluoikeus]] = None

    var komos: Map[String, Option[Komo]] = Map()

    var oid: Option[String] = None
    var hetu: Option[String] = None

    val resolver = Promise[Ensikertalainen]()
    val result: Future[Ensikertalainen] = resolver.future

    var virtaQuerySent = false

    logger.debug("starting queryActor")

    override def receive: Actor.Receive = {
      case ReportStatus =>
        val state = this match {
          case _ if result.isCompleted => result.value.flatMap(
            _.recover{case ex => "failed: " + ex.getMessage}.map((queryRes) => "done " + queryRes).toOption).getOrElse("empty")
          case _ if virtaQuerySent => "querying virta"
          case _ if suoritukset.isEmpty && opiskeluOikeudet.isEmpty =>  "resolving suoritukset and opinto-oikeudet"
          case _ if suoritukset.isEmpty =>  "resolving suoritukset"
          case _ if opiskeluOikeudet.isEmpty && !foundAllKomos =>  "resolving opinto-oikeudet and komos"
          case _ if opiskeluOikeudet.isDefined && !foundAllKomos =>  "resolving komos"
          case _ if hetu.isEmpty && !virtaQuerySent => "resolving hetu"
          case _ if hetu.isDefined && !virtaQuerySent => "resolving hetu internally"
          case _ => "unknown"
        }
        sender ! QueryStatus(state)


      case EnsikertalainenQuery(henkiloOid, henkiloHetu) =>
        oid = Some(henkiloOid)
        hetu = henkiloHetu
        logger.debug(s"starting query for requestor: $sender with oid $henkiloOid and ${henkiloHetu.map("hetu: " + _).getOrElse("no hetu")}")
        result pipeTo sender onComplete { res =>
          logger.debug(s"resolved with $res")
          if (res.isSuccess) context.parent ! CacheResult(EnsikertalainenQuery(henkiloOid, henkiloHetu), result)
          context.stop(self)
        }
        requestSuoritukset(henkiloOid)
        requestOpiskeluOikeudet(henkiloOid)

      case SuoritusResponse(suor) =>
        logger.debug(s"find suoritukset $suor")
        suoritukset = Some(suor)
        requestKomos(suor)
        if (suor.isEmpty && opiskeluOikeudet.isDefined) fetchHetu()


      case OpiskeluoikeusResponse(oo) =>
        logger.debug(s"find opiskeluoikeudet $oo")
        opiskeluOikeudet = Some(oo.filter(_.aika.alku.isAfter(kesa2014)))
        if (opiskeluOikeudet.getOrElse(Seq()).nonEmpty) resolveQuery(ensikertalainen = false)
        else if (foundAllKomos) {
          logger.debug("found all komos for opiskeluoikeudet, fetching hetu")
          fetchHetu()
        }

      case k: KomoResponse =>
        logger.debug(s"got komo $k")
        komos += (k.oid -> k.komo)
        if (foundAllKomos) {
          logger.debug(s"found all komos")
          val kkTutkinnot = for (
            suoritus <- suoritukset.getOrElse(Seq())
            if isKkTutkinto(suoritus)
          ) yield suoritus
          logger.debug(s"kktutkinnot: ${kkTutkinnot.toList}")
          if (kkTutkinnot.nonEmpty) resolveQuery(ensikertalainen = false)
          else if (opiskeluOikeudet.isDefined) {
            logger.debug("fetching hetus for suoritukset")
            fetchHetu()
          }
        }

      case HenkiloResponse(_, Some(h)) =>
        logger.debug(s"fetching virta with hetu $h")
        fetchVirta(h)

      case HenkiloResponse(_, None) =>
        logger.error(s"henkilo response failed, no hetu for oid $oid")
        failQuery(NoHetuException(oid, s"no hetu found for oid $oid"))

      case VirtaData(virtaOpiskeluOikeudet, virtaSuoritukset) =>
        logger.debug(s"got virta result opiskeluoikeudet: $virtaOpiskeluOikeudet, suoritukset: $virtaSuoritukset")
        val filteredOpiskeluOikeudet = virtaOpiskeluOikeudet.filter(_.aika.alku.isAfter(kesa2014))
        saveVirtaResult(filteredOpiskeluOikeudet, virtaSuoritukset)
        resolveQuery(filteredOpiskeluOikeudet.isEmpty ||  virtaSuoritukset.isEmpty)

      case akka.actor.Status.Failure(e: Throwable) =>
        logger.error(e, s"got error from $sender")
        failQuery(e)
    }


    def isKkTutkinto(suoritus: Suoritus): Boolean = suoritus match{
      case s: VirallinenSuoritus =>  komos.get(s.komo).exists(_.exists(_.isKorkeakoulututkinto))
      case s: VapaamuotoinenSuoritus => s.kkTutkinto
    }

    def foundAllKomos: Boolean = suoritukset match {
      case None => false
      case Some(s) => s.forall{
        case suoritus:VirallinenSuoritus => komos.get(suoritus.komo).isDefined
        case suoritus:VapaamuotoinenSuoritus => true
      }
    }

    def fetchHetu() = (oid, hetu) match {
      case (_, Some(h)) => fetchVirta(h)
      case (Some(o), None) => henkiloActor ! o
      case (None, None) => failQuery(NoHetuException(None, "No oid or hetu"))
    }

    def fetchVirta(hetu: String) = {
      virtaActor ! VirtaQuery(oid.get, Some(hetu))
      virtaQuerySent = true

    }

    def resolveQuery(ensikertalainen: Boolean) {
      resolve(Success(Ensikertalainen(ensikertalainen = ensikertalainen)))
    }

    def failQuery(failure: Throwable) {
      resolve(Failure(failure))
    }

    def resolve(message: Try[Ensikertalainen]) {
      logger.debug(s"resolving with message $message")
      resolver.complete(message)
    }

    def requestOpiskeluOikeudet(henkiloOid: String)  {
      context.actorOf(Props(new FetchResource[Opiskeluoikeus, OpiskeluoikeusResponse](OpiskeluoikeusQuery(henkilo = Some(henkiloOid)), OpiskeluoikeusResponse, self, opiskeluoikeusActor)))
    }

    case class OpiskeluoikeusResponse(opiskeluoikeudet: Seq[Opiskeluoikeus])

    def requestKomos(suoritukset: Seq[Suoritus]) {
      for (
        suoritus <- suoritukset.collect{case s: VirallinenSuoritus => s}
      ) if (suoritus.komo.startsWith("koulutus_")) self ! KomoResponse(suoritus.komo, Some(Komo(suoritus.komo, Koulutuskoodi(suoritus.komo.substring(9)), "TUTKINTO", "KORKEAKOULUTUS"))) else tarjontaActor ! GetKomoQuery(suoritus.komo)
    }

    def requestSuoritukset(henkiloOid: String) {
      context.actorOf(Props(new FetchResource[Suoritus, SuoritusResponse](SuoritusQuery(henkilo = Some(henkiloOid)), SuoritusResponse, self, suoritusActor)))
    }

    case class SuoritusResponse(suoritukset: Seq[Suoritus])

    def saveVirtaResult(opiskeluoikeudet: Seq[Opiskeluoikeus], suoritukset: Seq[Suoritus]) {
      logger.debug(s"saving virta result: opiskeluoikeudet size ${opiskeluoikeudet.size}, suoritukset size ${suoritukset.size}")
      opiskeluoikeudet.foreach(opiskeluoikeusActor ! _)
      suoritukset.foreach(suoritusActor ! _)
    }
  }

  class FetchResource[T, R](query: Query[T], wrapper: (Seq[T]) => R, receiver: ActorRef, resourceActor: ActorRef) extends Actor {
    override def preStart(): Unit = {
      resourceActor ! query
    }

    override def receive: Actor.Receive = {
      case s: Seq[T] =>
        receiver ! wrapper(s)
        context.stop(self)
    }
  }

  override def preStart(): Unit = {
    hakemukset ! Trigger((oid, hetu) => self ! EnsikertalainenQuery(oid, Some(hetu)))
    super.preStart()
  }
}


case class QueryStatus(status: String)

object ReportStatus


case class NoHetuException(oid: Option[String], message: String) extends NoSuchElementException(message)


