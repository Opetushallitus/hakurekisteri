package fi.vm.sade.hakurekisteri.integration.cas

case class InvalidServiceTicketException(m: String) extends Exception(s"service ticket is not valid: $m")
