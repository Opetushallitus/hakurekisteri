package fi.vm.sade.hakurekisteri.integration.cas


sealed class CasException(m: String) extends Exception(m)
case class InvalidServiceTicketException(m: String) extends CasException(s"service ticket is not valid: $m")
case class STWasNotCreatedException(m: String) extends CasException(m)
case class TGTWasNotCreatedException(m: String) extends CasException(m)
case class LocationHeaderNotFoundException(m: String) extends CasException(m)


