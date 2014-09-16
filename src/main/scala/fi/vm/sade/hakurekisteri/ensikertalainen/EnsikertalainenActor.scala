package fi.vm.sade.hakurekisteri.ensikertalainen

import akka.actor.{Props, Actor, ActorRef}
import akka.event.Logging
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.integration.henkilo.HenkiloResponse
import fi.vm.sade.hakurekisteri.integration.tarjonta.{KomoResponse, Komo, GetKomoQuery}
import fi.vm.sade.hakurekisteri.integration.virta.{VirtaData, VirtaQuery}
import fi.vm.sade.hakurekisteri.opiskeluoikeus.{Opiskeluoikeus, OpiskeluoikeusQuery}
import fi.vm.sade.hakurekisteri.suoritus.{SuoritusQuery, Suoritus}
import org.joda.time.{DateTime, LocalDate}
import akka.pattern.pipe

import scala.concurrent.{Promise, Future, ExecutionContext}
import scala.concurrent.duration._
import fi.vm.sade.hakurekisteri.integration.hakemus.Trigger

import scala.util.{Failure, Success, Try}

case class EnsikertalainenQuery(henkiloOid: String)

class EnsikertalainenActor(suoritusActor: ActorRef, opiskeluoikeusActor: ActorRef, virtaActor: ActorRef, henkiloActor: ActorRef, tarjontaActor: ActorRef, hakemukset : ActorRef)(implicit val ec: ExecutionContext) extends Actor {
  val logger = Logging(context.system, this)
  val kesa2014: DateTime = new LocalDate(2014, 7, 1).toDateTimeAtStartOfDay
  implicit val defaultTimeout: Timeout = 15.seconds

  override def receive: Receive = {
    case EnsikertalainenQuery(oid) =>
      logger.debug(s"EnsikertalainenQuery($oid)")
      context.actorOf(Props(new EnsikertalaisuusCheck())).forward(oid)
  }

  class EnsikertalaisuusCheck()  extends Actor {


    var suoritukset:Option[Seq[Suoritus]]  = None

    var opiskeluOikeudet:Option[Seq[Opiskeluoikeus]] = None

    var komos: Map[String, Option[Komo]] = Map()

    var oid:Option[String] = None

    val resolver = Promise[Ensikertalainen]
    val result:Future[Ensikertalainen] = resolver.future

    result.onComplete(
      _ =>
        context.stop(self)
    )

    override def receive: Actor.Receive = {
      case henkiloOid:String =>
        oid = Some(henkiloOid)
        logger.debug(s"starting query for requestor: $sender with oid $oid")
        result pipeTo sender
        requestSuoritukset(henkiloOid)
        requestOpiskeluOikeudet(henkiloOid)
      case s:Seq[_] if s.forall(_.isInstanceOf[Suoritus]) =>
        logger.debug(s"find suoritukset $s")
        val suor = s.map(_.asInstanceOf[Suoritus])
        suoritukset = Some(suor)
        requestKomos(suor)
      case k:KomoResponse =>
        logger.debug(s"got komo $k")
        komos += (k.oid -> k.komo)
        if (foundAllKomos) {
          logger.debug(s"found all komos")
          val kkTutkinnot = for (
            suoritus <- suoritukset.getOrElse(Seq())
            if komos.get(suoritus.komo).exists(_.exists(_.isKorkeakoulututkinto))
          ) yield suoritus
          logger.debug(s"kktutkinnot: $kkTutkinnot")
          if (!kkTutkinnot.isEmpty) resolveQuery(ensikertalainen = false)
          else if (opiskeluOikeudet.isDefined) {
            logger.debug("fetching hetus for suoritukset")
            fetchHetu()
          }
        }
      case s:Seq[_] if s.forall(_.isInstanceOf[Opiskeluoikeus]) =>
        logger.debug(s"find opiskeluoikeudet $s")
        opiskeluOikeudet = Some(s.map(_.asInstanceOf[Opiskeluoikeus]).filter(_.aika.alku.isAfter(kesa2014)))
        if (!opiskeluOikeudet.getOrElse(Seq()).isEmpty) resolveQuery(ensikertalainen = false)
        else if (foundAllKomos) {
          logger.debug("found all komos for opiskeluoikeudet, fetching hetu")
          fetchHetu()
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
    }

    def foundAllKomos: Boolean = suoritukset match {
      case None => false
      case Some(s) => s.forall((suoritus) => komos.get(suoritus.komo).isDefined)
    }

    def fetchHetu() = henkiloActor ! oid

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
      opiskeluoikeusActor ! OpiskeluoikeusQuery(henkilo = Some(henkiloOid))
    }

    def requestKomos(suoritukset: Seq[Suoritus]) {
      for (
        suoritus <- suoritukset
      ) if (suoritus.komo.startsWith("koulutus_")) self ! KomoResponse(suoritus.komo, Some(Komo(suoritus.komo, "TUTKINTO", "KORKEAKOULUTUS"))) else tarjontaActor ! GetKomoQuery(suoritus.komo)
    }

    def requestSuoritukset(henkiloOid: String) {
      suoritusActor ! SuoritusQuery(henkilo = Some(henkiloOid))
    }

    def saveVirtaResult(opiskeluoikeudet: Seq[Opiskeluoikeus], suoritukset: Seq[Suoritus]) {
      logger.debug(s"saving virta result: opiskeluoikeudet size ${opiskeluoikeudet.size}, suoritukset size ${suoritukset.size}")
      opiskeluoikeudet.foreach(opiskeluoikeusActor ! _)
      suoritukset.foreach(suoritusActor ! _)
    }
  }

  override def preStart(): Unit = {
    hakemukset ! Trigger((oid, hetu) => self ! EnsikertalainenQuery(oid))
    super.preStart()
  }
}
