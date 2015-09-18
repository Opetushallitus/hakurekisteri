package fi.vm.sade.hakurekisteri.integration.valintarekisteri

import akka.actor.{ActorLogging, Actor}

class ValintarekisteriActor extends Actor with ActorLogging {

  override def receive: Receive = {
    case henkiloOid: String =>
      sender ! None
  }

}
