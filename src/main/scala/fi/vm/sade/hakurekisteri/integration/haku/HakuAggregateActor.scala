package fi.vm.sade.hakurekisteri.integration.haku

import akka.actor.{Actor, ActorLogging, ActorRef}
import support.TypedActorRef

class HakuAggregateActor extends Actor with ActorLogging {
  override def receive: Receive = ???
}

case class HakuAggregateActorRef(actor: ActorRef) extends TypedActorRef
