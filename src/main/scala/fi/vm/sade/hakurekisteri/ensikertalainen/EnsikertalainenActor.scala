package fi.vm.sade.hakurekisteri.ensikertalainen


import akka.actor.{ActorLogging, Actor, ActorRef, Props}
import akka.pattern.pipe
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.integration.FutureCache
import fi.vm.sade.hakurekisteri.integration.tarjonta.{GetKomoQuery, Komo, KomoResponse, Koulutuskoodi}
import fi.vm.sade.hakurekisteri.opiskeluoikeus.{Opiskeluoikeus, OpiskeluoikeusQuery}
import fi.vm.sade.hakurekisteri.rest.support.Query
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, SuoritusQuery, VapaamuotoinenSuoritus, VirallinenSuoritus}
import org.joda.time.{DateTime, LocalDate}

import scala.collection.immutable.Iterable
import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

case class EnsikertalainenQuery(henkiloOid: String)

object QueryCount

case class QueriesRunning(count: Map[String, Int], timestamp: Long = Platform.currentTime)

case class CacheResult(q: EnsikertalainenQuery, f: Future[Ensikertalainen])

class EnsikertalainenActor(suoritusActor: ActorRef, opiskeluoikeusActor: ActorRef, tarjontaActor: ActorRef)(implicit val ec: ExecutionContext) extends Actor with ActorLogging {
  val kesa2014: DateTime = new LocalDate(2014, 7, 1).toDateTimeAtStartOfDay
  implicit val defaultTimeout: Timeout = 30.seconds
  private val cache = new FutureCache[EnsikertalainenQuery, Ensikertalainen](2.hours.toMillis)

  override def receive: Receive = {
    case q: EnsikertalainenQuery =>
      if (cache.contains(q)) cache.get(q) pipeTo sender
      else {
        log.debug(q.toString)
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

  case class EnsikertalaisuusCheckFailed(status: QueryStatus) extends Exception(s"ensikertalaisuus check failed: $status")
  
  class EnsikertalaisuusCheck() extends Actor with ActorLogging {
    var suoritukset: Option[Seq[Suoritus]] = None

    var opiskeluOikeudet: Option[Seq[Opiskeluoikeus]] = None

    var komos: Map[String, Option[Komo]] = Map()

    var oid: Option[String] = None

    val resolver = Promise[Ensikertalainen]()
    val result: Future[Ensikertalainen] = resolver.future

    context.system.scheduler.scheduleOnce(2.minutes)(failQuery(EnsikertalaisuusCheckFailed(getStatus)))
    
    def getStatus: QueryStatus = {
      val state = this match {
        case _ if result.isCompleted => result.value.flatMap(
          _.recover{case ex => "failed: " + ex.getMessage}.map((queryRes) => "done " + queryRes).toOption).getOrElse("empty")
        case _ if suoritukset.isEmpty && opiskeluOikeudet.isEmpty =>  "resolving suoritukset and opinto-oikeudet"
        case _ if suoritukset.isEmpty =>  "resolving suoritukset"
        case _ if opiskeluOikeudet.isEmpty && !foundAllKomos =>  "resolving opinto-oikeudet and komos"
        case _ if opiskeluOikeudet.isDefined && !foundAllKomos =>  "resolving komos"
        case _ => "unknown"
      }
      QueryStatus(state)
    }

    override def receive: Actor.Receive = {
      case ReportStatus =>
        sender ! getStatus

      case EnsikertalainenQuery(henkiloOid) =>
        oid = Some(henkiloOid)
        result pipeTo sender onComplete { res =>
          if (res.isSuccess) context.parent ! CacheResult(EnsikertalainenQuery(henkiloOid), result)
          context.stop(self)
        }
        requestSuoritukset(henkiloOid)
        requestOpiskeluOikeudet(henkiloOid)

      case SuoritusResponse(suor) =>
        suoritukset = Some(suor)
        if (suoritukset.exists {
          case v: VapaamuotoinenSuoritus => v.kkTutkinto
          case _ => false
        }) resolveQuery(ensikertalainen = false) else {

          requestKomos(suor)
          if (suor.collect{ case v: VirallinenSuoritus => v}.isEmpty && opiskeluOikeudet.isDefined) resolveQuery(true)
        }

      case OpiskeluoikeusResponse(oo) =>
        opiskeluOikeudet = Some(oo.filter(_.aika.alku.isAfter(kesa2014)))
        if (opiskeluOikeudet.getOrElse(Seq()).nonEmpty) resolveQuery(ensikertalainen = false)
        else if (foundAllKomos) {
          resolveQuery(true)
        }

      case k: KomoResponse =>
        komos += (k.oid -> k.komo)
        if (foundAllKomos) {
          val kkTutkinnot = for (
            suoritus <- suoritukset.getOrElse(Seq())
            if isKkTutkinto(suoritus)
          ) yield suoritus
          if (kkTutkinnot.nonEmpty) resolveQuery(ensikertalainen = false)
          else if (opiskeluOikeudet.isDefined) {
            resolveQuery(true)
          }
        }

      case akka.actor.Status.Failure(e: Throwable) =>
        log.error(e, s"got error from ${sender()}")
        failQuery(e)
    }


    def isKkTutkinto(suoritus: Suoritus): Boolean = suoritus match{
      case s: VirallinenSuoritus => komos.get(s.komo).exists(_.exists(_.isKorkeakoulututkinto))
      case s: VapaamuotoinenSuoritus => s.kkTutkinto
    }

    def foundAllKomos: Boolean = suoritukset match {
      case None => false
      case Some(s) => s.forall{
        case suoritus: VirallinenSuoritus => komos.get(suoritus.komo).isDefined
        case suoritus: VapaamuotoinenSuoritus => true
      }
    }

    def resolveQuery(ensikertalainen: Boolean) {
      resolve(Success(Ensikertalainen(ensikertalainen = ensikertalainen)))
    }

    def failQuery(failure: Throwable) {
      resolve(Failure(failure))
    }

    def resolve(message: Try[Ensikertalainen]) {
      resolver.tryComplete(message)
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
}

case class QueryStatus(status: String)

object ReportStatus


