package fi.vm.sade.hakurekisteri.acceptance.tools

import akka.actor.{ActorRef, Actor}
import fi.vm.sade.hakurekisteri.rest.support.Query


class FakeAuthorizer(guarded:ActorRef) extends Actor {
  override def receive: FakeAuthorizer#Receive =  {
    case q:(Query[_], _) => guarded forward  q._1
    case message:AnyRef => guarded forward message
  }
}
