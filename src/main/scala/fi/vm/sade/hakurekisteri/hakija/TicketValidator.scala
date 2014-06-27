package fi.vm.sade.hakurekisteri.hakija

case class InvalidServiceTicketException(m: String) extends Exception {
  override def getMessage() = s"Service ticket is not valid: $m"
}

object TicketValidator {
  val validSt = "^ST-.{1,253}$".r

  def isValidSt(st: String): Boolean = st match {
    case validSt() => true
    case _ => false
  }
}
