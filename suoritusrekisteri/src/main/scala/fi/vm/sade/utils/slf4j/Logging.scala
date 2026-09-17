package fi.vm.sade.utils.slf4j

import org.slf4j.LoggerFactory

/**
  * Vendored from the archived Opetushallitus/scala-utils repository, module scala-logging_2.12
  * (previously fi.vm.sade:scala-logging_2.12, pulled in transitively by fi.vm.sade:scala-utils_2.12).
  * That repository is no longer maintained, so the code lives here instead. The package name is kept
  * unchanged so existing call sites work as before.
  */
trait Logging {
  protected lazy val logger = LoggerFactory.getLogger(getClass())

  protected def withErrorLogging[T](f: => T)(errorMsg: String): T = {
    try {
      f
    } catch {
      case e: Exception =>
        logger.error(errorMsg, e)
        throw e
    }
  }

  protected def withWarnLogging[T](f: => T)(errorMsg: String, defaultValue: T): T = {
    try {
      f
    } catch {
      case e: Exception =>
        logger.warn(errorMsg, e)
        defaultValue
    }
  }
}
