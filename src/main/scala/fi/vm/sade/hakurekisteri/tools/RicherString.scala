package fi.vm.sade.hakurekisteri.tools

import scala.language.implicitConversions

class RicherString(orig: String) {
  def isBlank = orig == null || orig.trim.isEmpty
  def blankOption = if (isBlank) None else Some(orig)
}

object RicherString {
  implicit def stringToRicherString(s: String): RicherString = new RicherString(s)
}
