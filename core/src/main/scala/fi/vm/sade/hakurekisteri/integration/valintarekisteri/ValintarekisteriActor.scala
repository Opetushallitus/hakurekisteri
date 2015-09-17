package fi.vm.sade.hakurekisteri.integration.valintarekisteri

import akka.actor.Actor

class ValintarekisteriActor extends Actor {

  override def receive: Receive = {
    case henkiloOid: String =>
      sender ! None
  }

}
