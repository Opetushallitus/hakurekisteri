package fi.vm.sade.hakurekisteri.integration.haku

import scala.concurrent.{ExecutionContext, Future}
import akka.actor.{ActorRef, Actor}
import fi.vm.sade.hakurekisteri.integration.tarjonta.{RestHaku, GetHautQuery, RestHakuResult}
import fi.vm.sade.hakurekisteri.integration.parametrit.{HakuParams, KierrosRequest}
import akka.pattern.pipe
import fi.vm.sade.hakurekisteri.dates.{Ajanjakso, InFuture}
import org.joda.time.{DateTime, ReadableInstant}
import akka.event.Logging
import fi.vm.sade.hakurekisteri.integration.hakemus.ReloadHaku
import scala.concurrent.duration._
import org.scalatra.util.RicherString._
import fi.vm.sade.hakurekisteri.integration.sijoittelu.SijoitteluQuery


class HakuActor(tarjonta: ActorRef, parametrit: ActorRef, hakemukset: ActorRef, sijoittelu: ActorRef) extends Actor {

  implicit val ec = context.dispatcher

  var activeHakus:Seq[Haku] = Seq()

  val reloadHakemukset = context.system.scheduler.schedule(5.minutes, 2.hours, self, ReloadHakemukset)

  val reloadHakus = context.system.scheduler.schedule(1.second, 1.hours, self, ReloadHakemukset)

  val refreshTime = 2.hours

  val refreshSijoittelu = context.system.scheduler.schedule(1.second, refreshTime, self, RefreshSijoittelu)

  val log = Logging(context.system, this)

  override def preStart(): Unit = {
    self ! Update
    super.preStart()
  }

  import FutureList._

  override def receive: Actor.Receive = {
    case RefreshSijoittelu => refreshKeepAlives()
    case Update => tarjonta ! GetHautQuery
    case HakuRequest  => sender ! activeHakus
    case RestHakuResult(hakus: List[RestHaku]) =>
        enrich(hakus).waitForAll pipeTo self
    case s:Seq[Haku] =>
      activeHakus =  s.filter(_.aika.isCurrently)
      log.debug(s"current hakus ${activeHakus.mkString(", ")}")

    case ReloadHakemukset =>
      for(
        haku <- activeHakus
      )   hakemukset ! ReloadHaku(haku.oid)


  }


  def enrich(hakus: List[RestHaku]): List[Future[Haku]] = {
    for (
      haku <- hakus
      if haku.oid.isDefined && !haku.hakuaikas.isEmpty
    ) yield getKierrosEnd(haku.oid.get).map(Haku(haku))
  }

  def getKierrosEnd(hakuOid: String): Future[ReadableInstant] = {
    import akka.pattern.ask
    import akka.util.Timeout
    import scala.concurrent.duration._
    implicit val to:Timeout = 2.minutes
    (parametrit ? KierrosRequest(hakuOid)).mapTo[HakuParams].map(_.end).recover {
      case _ => InFuture
    }

  }

  def refreshKeepAlives() {
    activeHakus.zipWithIndex foreach {case (haku:Haku, i: Int) => context.system.scheduler.scheduleOnce(i.seconds, sijoittelu, SijoitteluQuery(haku.oid))(context.dispatcher, ActorRef.noSender)}
  }

}

object Update

object HakuRequest

object ReloadHakemukset

object RefreshSijoittelu

case class Kieliversiot(fi: Option[String], sv: Option[String], en: Option[String])

case class Haku(nimi: Kieliversiot, oid: String, aika: Ajanjakso, kausi: String, vuosi: Int)

object Haku {

  def apply(haku: RestHaku)(loppu: ReadableInstant): Haku = {
    val ajanjakso = Ajanjakso(findStart(haku), loppu)
    Haku(
      Kieliversiot(haku.nimi.get("kieli_fi").flatMap(Option(_)).flatMap(_.blankOption), haku.nimi.get("kieli_sv").flatMap(Option(_)).flatMap(_.blankOption), haku.nimi.get("kieli_en").flatMap(Option(_)).flatMap(_.blankOption)), haku.oid.get,
      ajanjakso,
      haku.hakukausiUri,
      haku.hakukausiVuosi
    )
  }

  def findStart(haku: RestHaku): DateTime = {
    new DateTime(haku.hakuaikas.map(_.alkuPvm).sorted.head)
  }
}


class FutureList[A](futures: Seq[Future[A]]) {


  def waitForAll(implicit ec: ExecutionContext):Future[Seq[A]] = Future.sequence(futures)
}

object FutureList {

  implicit def futures2FutureList[A](futures: Seq[Future[A]]): FutureList[A] = new FutureList(futures)

}
