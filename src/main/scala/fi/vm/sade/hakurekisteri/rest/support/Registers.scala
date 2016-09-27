package fi.vm.sade.hakurekisteri.rest.support

import akka.actor.ActorRef

trait Registers {
  val suoritusRekisteri: ActorRef
  val ytlSuoritusRekisteri: ActorRef
  val opiskelijaRekisteri: ActorRef
  val opiskeluoikeusRekisteri: ActorRef
  val arvosanaRekisteri: ActorRef
  val ytlArvosanaRekisteri: ActorRef
  val eraRekisteri: ActorRef
}
