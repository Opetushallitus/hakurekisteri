package fi.vm.sade.hakurekisteri.integration.virta

case class InvalidVirtaResponseException(message: String) extends Exception(message)
