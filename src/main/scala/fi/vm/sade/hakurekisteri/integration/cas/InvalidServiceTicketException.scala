package fi.vm.sade.hakurekisteri.integration.cas

case class InvalidServiceTicketException(m: String) extends RuntimeException(s"service ticket is not valid: $m")
