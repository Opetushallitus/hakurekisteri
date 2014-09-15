package fi.vm.sade.hakurekisteri.ensikertalainen

import akka.actor.{Props, Actor, ActorRef}
import akka.event.Logging
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.integration.henkilo.HenkiloResponse
import fi.vm.sade.hakurekisteri.integration.tarjonta.{Komo, GetKomoQuery}
import fi.vm.sade.hakurekisteri.integration.virta.{VirtaData, VirtaQuery}
import fi.vm.sade.hakurekisteri.opiskeluoikeus.{Opiskeluoikeus, OpiskeluoikeusQuery}
import fi.vm.sade.hakurekisteri.suoritus.{SuoritusQuery, Suoritus}
import org.joda.time.{DateTime, LocalDate}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import fi.vm.sade.hakurekisteri.integration.hakemus.Trigger
import akka.actor.Status.Failure

case class EnsikertalainenQuery(henkiloOid: String)

class EnsikertalainenActor(suoritusActor: ActorRef, opiskeluoikeusActor: ActorRef, virtaActor: ActorRef, henkiloActor: ActorRef, tarjontaActor: ActorRef, hakemukset : ActorRef)(implicit val ec: ExecutionContext) extends Actor {
  val logger = Logging(context.system, this)
  val kesa2014: DateTime = new LocalDate(2014, 7, 1).toDateTimeAtStartOfDay
  implicit val defaultTimeout: Timeout = 15.seconds

  override def receive: Receive = {
    case EnsikertalainenQuery(oid) =>
      logger.debug(s"EnsikertalainenQuery($oid)")
      context.actorOf(Props(new EnsikertalaisuusCheck(sender, oid)))
  }

  class EnsikertalaisuusCheck(requestor: ActorRef, oid: String)  extends Actor {

    var suoritukset:Option[Seq[Suoritus]]  = None

    var opiskeluOikeudet:Option[Seq[Opiskeluoikeus]] = None

    var komos: Map[String, Komo] = Map()

    override def preStart() {
      requestSuoritukset(oid)
      requestOpiskeluOikeudet(oid)
    }

    override def receive: Actor.Receive = {
      case s:Seq[_] if s.forall(_.isInstanceOf[Suoritus]) =>
        val suor = s.map(_.asInstanceOf[Suoritus])
        suoritukset = Some(suor)
        requestKomos(suor)
      case k:Komo =>
        komos += (k.oid -> k)
        if (foundAllKomos) {
          val kkTutkinnot = for (
            suoritus <- suoritukset.getOrElse(Seq())
            if komos.get(suoritus.komo).exists(_.isKorkeakoulututkinto)
          ) yield suoritus
          if (!kkTutkinnot.isEmpty) resolveQuery(ensikertalainen = false)
          else if (opiskeluOikeudet.isDefined) fetchHetu()
        }
      case s:Seq[_] if s.forall(_.isInstanceOf[Opiskeluoikeus]) =>
        opiskeluOikeudet = Some(s.map(_.asInstanceOf[Opiskeluoikeus]).filter(_.aika.alku.isAfter(kesa2014)))
        if (!opiskeluOikeudet.getOrElse(Seq()).isEmpty) resolveQuery(ensikertalainen = false)
        else if (foundAllKomos) fetchHetu()

      case HenkiloResponse(_, Some(hetu)) => fetchVirta(hetu)

      case HenkiloResponse(_, None) =>  failQuery(new NoSuchElementException(s"no hetu found for oid $oid"))

      case VirtaData(virtaOpiskeluOikeudet, virtaSuoritukset) =>
        val filteredOpiskeluOikeudet = virtaOpiskeluOikeudet.filter(_.aika.alku.isAfter(kesa2014))
        saveVirtaResult(filteredOpiskeluOikeudet, virtaSuoritukset)
        resolveQuery(filteredOpiskeluOikeudet.isEmpty ||  virtaSuoritukset.isEmpty)

    }

    def foundAllKomos: Boolean = suoritukset match {
      case None => false
      case Some(s) => s.forall((suoritus) => komos.get(suoritus.komo).isDefined)
    }

    def fetchHetu() = henkiloActor ! oid

    def fetchVirta(hetu: String) = virtaActor ! VirtaQuery(Some(oid), Some(hetu))

    def resolveQuery(ensikertalainen: Boolean) {
      resolve(Ensikertalainen(ensikertalainen = ensikertalainen))

    }

    def failQuery(failure: Throwable) {
      resolve(Failure(failure))

    }

    def resolve(message: Any) {
      requestor ! message
      context.stop(self)

    }

    def requestOpiskeluOikeudet(henkiloOid: String)  {
      opiskeluoikeusActor ! OpiskeluoikeusQuery(henkilo = Some(henkiloOid))
    }


    def requestKomos(suoritukset: Seq[Suoritus]) {
      for (
        suoritus <- suoritukset
      ) tarjontaActor ! GetKomoQuery(suoritus.komo)
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
