package fi.vm.sade.utils

import fi.vm.sade.utils.slf4j.Logging

/**
  * Vendored from the archived Opetushallitus/scala-utils repository, module scala-utils_2.12
  * (previously fi.vm.sade:scala-utils_2.12). That repository is no longer maintained, so the code
  * lives here instead. The package name is kept unchanged so existing call sites work as before.
  */
object Timer extends Logging {
  def timed[R](blockname: String = "", thresholdMs: Int = 0)(block: => R): R = {
    val t0 = System.nanoTime()
    val result = block
    val t1 = System.nanoTime()
    val time: Long = (t1 - t0) / 1000000
    if (time >= thresholdMs) logger.info(blockname + " call took: " + time + " ms")
    result
  }
}
