package fi.vm.sade.hakurekisteri.ensikertalainen

import akka.actor.{Actor, ActorRef}
import akka.event.Logging
import akka.pattern.{ask, pipe}
import akka.util.Timeout
import fi.vm.sade.generic.common.HetuUtils
import fi.vm.sade.hakurekisteri.integration.henkilo.HenkiloResponse
import fi.vm.sade.hakurekisteri.integration.tarjonta.{Komo, GetKomoQuery}
import fi.vm.sade.hakurekisteri.integration.virta.VirtaQuery
import fi.vm.sade.hakurekisteri.opiskeluoikeus.{Opiskeluoikeus, OpiskeluoikeusQuery}
import fi.vm.sade.hakurekisteri.suoritus.{SuoritusQuery, Suoritus}
import org.joda.time.{DateTime, LocalDate}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import fi.vm.sade.hakurekisteri.integration.hakemus.Trigger

case class EnsikertalainenQuery(henkiloOid: String)

class EnsikertalainenActor(suoritusActor: ActorRef, opiskeluoikeusActor: ActorRef, virtaActor: ActorRef, henkiloActor: ActorRef, tarjontaActor: ActorRef, hakemukset : ActorRef)(implicit val ec: ExecutionContext) extends Actor {
  val logger = Logging(context.system, this)
  val kesa2014: DateTime = new LocalDate(2014, 7, 1).toDateTimeAtStartOfDay
  implicit val defaultTimeout: Timeout = 15.seconds

  override def receive: Receive = {
    case EnsikertalainenQuery(oid) =>
      logger.debug(s"EnsikertalainenQuery($oid)")
      onkoEnsikertalainen(oid) map Ensikertalainen pipeTo sender
  }

  override def preStart(): Unit = {
    hakemukset ! Trigger((oid, hetu) => self ! EnsikertalainenQuery(oid))
    super.preStart()
  }

  def getHetu(henkilo: String): Future[String] = (henkiloActor ? henkilo).mapTo[HenkiloResponse].map(_.hetu match {
    case Some(hetu) if HetuUtils.isHetuValid(hetu) => hetu
    case Some(hetu) => logger.error(s"hetu $hetu not valid for oid $henkilo"); throw HetuNotFoundException(s"hetu not valid for oid $henkilo")
    case None => throw HetuNotFoundException(s"hetu not found with oid $henkilo")
  })

  def getKkTutkinnot(henkiloOid: String): Future[Seq[Suoritus]] = {
    for (
      suoritukset <- getSuoritukset(henkiloOid);
      tupled <- findKomos(suoritukset)
    ) yield tupled collect {
      case (komo, suoritus) if komo.isKorkeakoulututkinto => suoritus
    }
  }

  def findKomos(suoritukset: Seq[Suoritus]): Future[Seq[(Komo, Suoritus)]] = {
    Future.sequence(for (
      suoritus <- suoritukset
    ) yield (tarjontaActor ? GetKomoQuery(suoritus.komo)).mapTo[Option[Komo]].map((_, suoritus)).collect {
        case (Some(komo), foundsuoritus) => (komo, foundsuoritus)
      })
  }

  def getSuoritukset(henkiloOid: String): Future[Seq[Suoritus]] = {
    (suoritusActor ? SuoritusQuery(henkilo = Some(henkiloOid))).mapTo[Seq[Suoritus]]
  }

  def getKkOpiskeluoikeudet2014KesaJalkeen(henkiloOid: String): Future[Seq[Opiskeluoikeus]] = {
    for (
      opiskeluoikeudet <- (opiskeluoikeusActor ? OpiskeluoikeusQuery(henkilo = Some(henkiloOid))).mapTo[Seq[Opiskeluoikeus]]
    ) yield opiskeluoikeudet.filter(_.aika.alku.isAfter(kesa2014))
  }

  def onkoEnsikertalainen(henkiloOid: String): Future[Boolean] = {
    val tutkinnot: Future[Seq[Suoritus]] = getKkTutkinnot(henkiloOid).recover {
      case t:Throwable =>
        logger.warning("failed to find suoritus from local registers, checking virta", t)
        Seq()
    }
    val opiskeluoikeudet: Future[Seq[Opiskeluoikeus]] = getKkOpiskeluoikeudet2014KesaJalkeen(henkiloOid).recover {
      case t:Throwable =>
        logger.warning("failed to find opiskeluoikeus from local registers, checking virta", t)
        Seq()
    }

    tutkinnot.foreach(t => logger.debug(s"tutkinnot: $t"))
    opiskeluoikeudet.foreach(o => logger.debug(s"opiskeluoikeudet: $o"))

    anySequenceHasElements(tutkinnot, opiskeluoikeudet).flatMap (
      if (_) {
        logger.debug(s"has tutkinto or opiskeluoikeus")
        Future.successful(false)
      } else checkEnsikertalainenFromVirta(henkiloOid)
    )
  }

  def checkEnsikertalainenFromVirta(henkiloOid: String): Future[Boolean] = {
    val virtaResult: Future[(Seq[Opiskeluoikeus], Seq[Suoritus])] = getHetu(henkiloOid).flatMap((hetu) => (virtaActor ? VirtaQuery(Some(henkiloOid), Some(hetu)))(60.seconds).mapTo[(Seq[Opiskeluoikeus], Seq[Suoritus])])
    for ((opiskeluoikeudet, suoritukset) <- virtaResult) yield {
      val filteredOpiskeluoikeudet = opiskeluoikeudet.filter(_.aika.alku.isAfter(kesa2014))
      saveVirtaResult(filteredOpiskeluoikeudet, suoritukset)
      logger.debug(s"checked from virta: opiskeluoikeudet.isEmpty ${filteredOpiskeluoikeudet.isEmpty}, suoritukset.isEmpty ${suoritukset.isEmpty}")
      filteredOpiskeluoikeudet.isEmpty && suoritukset.isEmpty
    }
  }

  def saveVirtaResult(opiskeluoikeudet: Seq[Opiskeluoikeus], suoritukset: Seq[Suoritus]) {
    logger.debug(s"saving virta result: opiskeluoikeudet size ${opiskeluoikeudet.size}, suoritukset size ${suoritukset.size}")
    opiskeluoikeudet.foreach(opiskeluoikeusActor ! _)
    suoritukset.foreach(suoritusActor ! _)
  }

  def anySequenceHasElements(futures: Future[Seq[_]]*): Future[Boolean] = Future.find(futures){!_.isEmpty}.map{
    case None => false
    case Some(_) => true
  }
}
