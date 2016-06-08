package fi.vm.sade.hakurekisteri.integration.virta

import akka.actor.Status.Failure
import akka.actor.{ActorRef, ActorLogging, Actor}

import scala.concurrent.{Future, ExecutionContext}

class VirtaResourceActor(virtaClient: VirtaClient) extends Actor with ActorLogging {
  implicit val executionContext: ExecutionContext = context.dispatcher

  import akka.pattern.pipe

  def receive: Receive = {
    case q: VirtaQuery =>
      val tiedot: Future[Option[VirtaResult]] = getOpiskelijanTiedot(q.oppijanumero, q.hetu)
      tiedot pipeTo sender()

    case Failure(t: VirtaValidationError) =>
      log.warning(s"virta validation error: $t")

    case Failure(t: Throwable) =>
      log.error(t, "error occurred in virta query")
  }

  def getOpiskelijanTiedot(oppijanumero: String, hetu: Option[String]): Future[Option[VirtaResult]] = {
    virtaClient.getOpiskelijanTiedot(oppijanumero = oppijanumero, hetu = hetu)
  }

}
