package fi.vm.sade.hakurekisteri.integration.cas

object TicketValidator {
  val validSt = "^ST-.{1,253}$".r

  def isValidSt(st: String): Boolean = st match {
    case validSt() => true
    case _ => false
  }
}
