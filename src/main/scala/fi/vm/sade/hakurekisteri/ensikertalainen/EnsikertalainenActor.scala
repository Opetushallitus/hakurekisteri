package fi.vm.sade.hakurekisteri.ensikertalainen

import akka.actor.{Actor, Props, ActorRef}
import akka.event.Logging
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.integration.henkilo.HenkiloResponse
import fi.vm.sade.hakurekisteri.integration.tarjonta.{KomoResponse, Komo, GetKomoQuery}
import fi.vm.sade.hakurekisteri.integration.virta.{VirtaConnectionErrorException, VirtaData, VirtaQuery}
import fi.vm.sade.hakurekisteri.opiskeluoikeus.{Opiskeluoikeus, OpiskeluoikeusQuery}
import fi.vm.sade.hakurekisteri.rest.support.Query
import fi.vm.sade.hakurekisteri.suoritus.{SuoritusQuery, Suoritus}
import org.joda.time.{DateTime, LocalDate}
import akka.pattern.pipe

import scala.concurrent.{Promise, Future, ExecutionContext}
import scala.concurrent.duration._
import fi.vm.sade.hakurekisteri.integration.hakemus.Trigger

import scala.util.{Failure, Success, Try}

case class EnsikertalainenQuery(henkiloOid: String, hetu: Option[String]= None)

class EnsikertalainenActor(suoritusActor: ActorRef, opiskeluoikeusActor: ActorRef, virtaActor: ActorRef, henkiloActor: ActorRef, tarjontaActor: ActorRef, hakemukset : ActorRef)(implicit val ec: ExecutionContext) extends Actor {
  val logger = Logging(context.system, this)
  val kesa2014: DateTime = new LocalDate(2014, 7, 1).toDateTimeAtStartOfDay
  implicit val defaultTimeout: Timeout = 15.seconds

  override def receive: Receive = {
    case q:EnsikertalainenQuery =>
      logger.debug(s"EnsikertalainenQuery($q.oid) with ${q.hetu.map("hetu: " + _).getOrElse("no hetu")}")
      context.actorOf(Props(new EnsikertalaisuusCheck())).forward(q)
  }

  class EnsikertalaisuusCheck() extends Actor {
    var suoritukset: Option[Seq[Suoritus]] = None

    var opiskeluOikeudet: Option[Seq[Opiskeluoikeus]] = None

    var komos: Map[String, Option[Komo]] = Map()

    var oid: Option[String] = None
    var hetu: Option[String] = None

    val resolver = Promise[Ensikertalainen]
    val result: Future[Ensikertalainen] = resolver.future

    logger.debug("starting queryActor")

    override def receive: Actor.Receive = {
      case EnsikertalainenQuery(henkiloOid, henkiloHetu) =>
        oid = Some(henkiloOid)
        hetu = henkiloHetu
        logger.debug(s"starting query for requestor: $sender with oid $henkiloOid and ${henkiloHetu.map("hetu: " + _).getOrElse("no hetu")}")
        result pipeTo sender onComplete { res =>
          logger.debug(s"resolved with $res")
          context.stop(self)
        }
        requestSuoritukset(henkiloOid)
        requestOpiskeluOikeudet(henkiloOid)

      case SuoritusResponse(suor) =>
        logger.debug(s"find suoritukset $suor")
        suoritukset = Some(suor)
        requestKomos(suor)

      case OpiskeluoikeusResponse(oo) =>
        logger.debug(s"find opiskeluoikeudet $oo")
        opiskeluOikeudet = Some(oo.filter(_.aika.alku.isAfter(kesa2014)))
        if (!opiskeluOikeudet.getOrElse(Seq()).isEmpty) resolveQuery(ensikertalainen = false)
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
            if komos.get(suoritus.komo).exists(_.exists(_.isKorkeakoulututkinto))
          ) yield suoritus
          logger.debug(s"kktutkinnot: ${kkTutkinnot.toList}")
          if (!kkTutkinnot.isEmpty) resolveQuery(ensikertalainen = false)
          else if (opiskeluOikeudet.isDefined) {
            logger.debug("fetching hetus for suoritukset")
            fetchHetu()
          }
        }

      case HenkiloResponse(_, Some(hetu)) =>
        logger.debug(s"fetching virta with hetu $hetu")
        fetchVirta(hetu)

      case HenkiloResponse(_, None) =>
        logger.error(s"henkilo response failed, no hetu for oid $oid")
        failQuery(new NoSuchElementException(s"no hetu found for oid $oid"))

      case VirtaData(virtaOpiskeluOikeudet, virtaSuoritukset) =>
        logger.debug(s"got virta result opiskeluoikeudet: $virtaOpiskeluOikeudet, suoritukset: $virtaSuoritukset")
        val filteredOpiskeluOikeudet = virtaOpiskeluOikeudet.filter(_.aika.alku.isAfter(kesa2014))
        saveVirtaResult(filteredOpiskeluOikeudet, virtaSuoritukset)
        resolveQuery(filteredOpiskeluOikeudet.isEmpty ||  virtaSuoritukset.isEmpty)

      case akka.actor.Status.Failure(e: VirtaConnectionErrorException) =>
        logger.error(e, "error in virta")
        failQuery(e)
    }

    def foundAllKomos: Boolean = suoritukset match {
      case None => false
      case Some(s) => s.forall((suoritus) => komos.get(suoritus.komo).isDefined)
    }

    def fetchHetu() =


    try {
      if (hetu.isDefined)
        (self ! HenkiloResponse(oid.get, hetu))(ActorRef.noSender)
      else
        henkiloActor ! oid.get
    } catch {
      case e: Throwable => failQuery(e)
    }

    def fetchVirta(hetu: String) = virtaActor ! VirtaQuery(oid, Some(hetu))

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
        suoritus <- suoritukset
      ) if (suoritus.komo.startsWith("koulutus_")) self ! KomoResponse(suoritus.komo, Some(Komo(suoritus.komo, "TUTKINTO", "KORKEAKOULUTUS"))) else tarjontaActor ! GetKomoQuery(suoritus.komo)
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
