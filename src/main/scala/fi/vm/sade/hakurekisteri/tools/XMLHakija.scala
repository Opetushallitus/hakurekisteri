package fi.vm.sade.hakurekisteri.tools

class RicherString(orig: String) {
  def isBlank = orig == null || orig.trim.isEmpty

  def blankOption = if (isBlank) None else Some(orig)

}

object RicherString {
  implicit def stringToRicherString(s: String) = new RicherString(s)
}


