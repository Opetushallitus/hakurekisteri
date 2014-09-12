package fi.vm.sade.hakurekisteri.integration.haku

import scala.concurrent.Future
import akka.actor.{ActorRef, Actor}
import fi.vm.sade.hakurekisteri.integration.tarjonta.{RestHaku, GetHautQuery, RestHakuResult}
import fi.vm.sade.hakurekisteri.integration.parametrit.{HakuParams, KierrosRequest}
import akka.pattern.pipe
import fi.vm.sade.hakurekisteri.dates.{InFuture, Ajanjakso}
import org.joda.time.{DateTime, ReadableInstant}
import akka.event.Logging
import fi.vm.sade.hakurekisteri.integration.hakemus.ReloadHaku
import scala.concurrent.duration._


class HakuActor(tarjonta: ActorRef, parametrit: ActorRef, hakemukset: ActorRef) extends Actor {

  implicit val ec = context.dispatcher

  var activeHakus:Seq[Haku] = Seq()

  val reloadHakemukset = context.system.scheduler.schedule(1.second, 2.hours, self, ReloadHakemukset)


  val log = Logging(context.system, this)

  override def preStart(): Unit = {
    self ! Update
    super.preStart()
  }

  override def receive: Actor.Receive = {

    case Update => tarjonta ! GetHautQuery
    case HakuRequest  => sender ! activeHakus
    case RestHakuResult(hakus: List[RestHaku]) =>
        Future.sequence(for (
          haku <- hakus
          if haku.oid.isDefined && !haku.hakuaikas.isEmpty
        ) yield {
          val startTime = new DateTime(haku.hakuaikas.map(_.alkuPvm).sorted.head)
          getKierrosEnd(haku.oid.get).recover{ case _ => InFuture }.map((end) => {
            val ajanjakso = Ajanjakso(startTime, end)
            Haku(Kieliversiot(haku.nimi.get("kieli_fi").flatMap(Option(_)), haku.nimi.get("kieli_sv").flatMap(Option(_)), haku.nimi.get("kieli_en").flatMap(Option(_))),haku.oid.get, ajanjakso)
          })
        }) pipeTo self

    case s:Seq[Haku] =>
      activeHakus =  s.filter(_.aika.isCurrently)
      log.debug(s"current hakus ${activeHakus.mkString(", ")}")

    case ReloadHakemukset =>
      for(
        haku <- activeHakus
      )   hakemukset ! ReloadHaku(haku.oid)


  }


  def getKierrosEnd(hakuOid: String): Future[ReadableInstant] = {
    import akka.pattern.ask
    import akka.util.Timeout
    import scala.concurrent.duration._
    implicit val to:Timeout = 2.minutes
    (parametrit ? KierrosRequest(hakuOid)).mapTo[HakuParams].map(_.end)

  }

}

object Update

object HakuRequest

object ReloadHakemukset

case class Kieliversiot(fi: Option[String], sv: Option[String], en: Option[String])

case class Haku(nimi: Kieliversiot, oid: String, aika: Ajanjakso)

