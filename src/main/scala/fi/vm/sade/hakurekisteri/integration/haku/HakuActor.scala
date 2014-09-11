package fi.vm.sade.hakurekisteri.integration.haku

import scala.concurrent.Future
import spray.http.DateTime
import akka.actor.{ActorRef, Actor}
import fi.vm.sade.hakurekisteri.integration.tarjonta.{RestHaku, GetHautQuery, RestHakuResult}
import fi.vm.sade.hakurekisteri.integration.parametrit.{HakuParams, KierrosRequest}
import akka.pattern.pipe
import fi.vm.sade.hakurekisteri.dates.Ajanjakso
import org.joda.time.ReadableInstant
import akka.event.Logging


class HakuActor(tarjonta: ActorRef, parametrit: ActorRef) extends Actor {

  implicit val ec = context.dispatcher

  var activeHakus:Seq[Haku] = Seq()

  val log = Logging(context.system, this)

  override def preStart(): Unit = {
    self ! Update
    super.preStart
  }

  override def receive: Actor.Receive = {

    case Update => tarjonta ! GetHautQuery
    case HakuRequest  => sender ! activeHakus
    case RestHakuResult(hakus: List[RestHaku]) =>
        Future.sequence(for (
          haku <- hakus
          if haku.oid.isDefined && !haku.hakuaikas.isEmpty
        ) yield {
          val startTime = DateTime(haku.hakuaikas.map(_.alkuPvm).sorted.head)
          getKierrosEnd(haku.oid.get).map((end) => {
            val ajanjakso = Ajanjakso(startTime.asInstanceOf[ReadableInstant], end.asInstanceOf[ReadableInstant])
            Haku(haku.oid.get, ajanjakso)
          })
        }) pipeTo self

    case s:Seq[Haku] =>
      activeHakus =  s.filter(_.aika.isCurrently)
      log.debug(s"current hakus ${activeHakus.mkString(", ")}")


  }


  def getKierrosEnd(hakuOid: String): Future[DateTime] = {
    import akka.pattern.ask
    import akka.util.Timeout
    import scala.concurrent.duration._
    implicit val to:Timeout = 2.minutes
    (parametrit ? KierrosRequest(hakuOid)).mapTo[HakuParams].map(_.end)

  }

}

object Update

object HakuRequest

case class Haku(oid: String, aika: Ajanjakso)

