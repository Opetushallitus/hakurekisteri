package fi.vm.sade.utils.tcp

import java.io.IOException
import java.net.Socket

import scala.util.Random

/**
  * Vendored from the archived Opetushallitus/scala-utils repository, module scala-utils_2.12
  * (previously fi.vm.sade:scala-utils_2.12). That repository is no longer maintained, so the code
  * lives here instead. The package name is kept unchanged so existing call sites work as before.
  *
  * Behaviour is that of the original, except that the postfix `range length` was rewritten as
  * `range.length`: postfix operators warn under -feature, and -Xfatal-warnings turns that into a
  * build failure.
  */
object PortChecker {
  def isFreeLocalPort(port: Int): Boolean = {
    try {
      val socket = new Socket("127.0.0.1", port)
      socket.close()
      false
    } catch {
      case _: IOException => true
    }
  }

  def findFreeLocalPort: Int = {
    val range = 1024 to 60000
    val port = range(new Random().nextInt(range.length))
    if (isFreeLocalPort(port)) {
      port
    } else {
      findFreeLocalPort
    }
  }
}
