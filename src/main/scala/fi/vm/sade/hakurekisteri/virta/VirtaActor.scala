package fi.vm.sade.hakurekisteri.virta

import akka.actor.Actor
import akka.event.Logging
import akka.pattern.pipe
import fi.vm.sade.hakurekisteri.suoritus.Suoritus

import scala.concurrent.{Future, ExecutionContext}

class VirtaActor(virtaClient: VirtaClient) extends Actor {
  implicit val executionContext: ExecutionContext = context.dispatcher
  val log = Logging(context.system, this)

  def receive: Receive = {
    case (oppijanumero: Option[String], hetu: Option[String]) => convert(virtaClient.getOpiskelijanTiedot(oppijanumero = oppijanumero, hetu = hetu)) pipeTo sender
  }

  def convert(f: Future[Option[VirtaResult]]): Future[Seq[Suoritus]] = {
    Future.successful(Seq()) // TODO
  }
}
