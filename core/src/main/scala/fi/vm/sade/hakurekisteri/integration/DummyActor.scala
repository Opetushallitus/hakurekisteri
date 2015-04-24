package fi.vm.sade.hakurekisteri.integration

import akka.actor.Actor

class DummyActor extends Actor {
  override def receive: Receive = {
    case x => println("DummyActor: got " + x)
  }
}
