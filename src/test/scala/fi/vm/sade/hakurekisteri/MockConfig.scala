package fi.vm.sade.hakurekisteri

import java.nio.file.Paths

import fi.vm.sade.hakurekisteri.tools.ItPostgres
import support.SureDbLoggingConfig

import scala.concurrent.duration._

class MockConfig extends Config {
  def mockMode = true
  log.info("Using mock config")
  val dbPort = ItPostgres.port
  override val databaseUrl = s"jdbc:postgresql://localhost:$dbPort/suoritusrekisteri"
  override val databaseHost = "localhost"
  override val databasePort = dbPort.toString
  override val postgresUser = null //properties.getOrElse("suoritusrekisteri.db.user", "postgres")
  override val postgresPassword = null
  override val archiveNonCurrentAfterDays = "180"

  private val defaultDbLoggingConfig = SureDbLoggingConfig()
  override val slowQuery: Long = defaultDbLoggingConfig.slowQueryMillis
  override val reallySlowQuery: Long = defaultDbLoggingConfig.reallySlowQueryMillis
  override val maxDbLogLineLength: Int = defaultDbLoggingConfig.maxLogLineLength

  override val importBatchProcessingInitialDelay = 1.seconds
  lazy val ophConfDir = Paths.get(ProjectRootFinder.findProjectRoot().getAbsolutePath, "src/test/resources/oph-configuration")
  override val valintaTulosTimeout: FiniteDuration = 1.minute
}
