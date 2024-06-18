package fi.vm.sade.hakurekisteri

import java.nio.file.Paths

import akka.util.Timeout
import fi.vm.sade.hakurekisteri.tools.ItPostgres
import support.SureDbLoggingConfig

import scala.concurrent.duration._

class MockConfig extends Config {
  def mockMode = true
  log.info("Using mock config")
  val dbPort = ItPostgres.port
  override val databaseUrl = ItPostgres.getEndpointURL
  override val databaseHost = "localhost"
  override val databasePort = dbPort.toString
  override val postgresUser = ItPostgres.container.username
  override val postgresPassword = ItPostgres.container.password
  override val archiveNonCurrentAfterDays = "180"
  override val archiveBatchSize = "3"

  private val defaultDbLoggingConfig = SureDbLoggingConfig()
  override val slowQuery: Long = defaultDbLoggingConfig.slowQueryMillis
  override val reallySlowQuery: Long = defaultDbLoggingConfig.reallySlowQueryMillis
  override val maxDbLogLineLength: Int = defaultDbLoggingConfig.maxLogLineLength

  override val importBatchProcessingInitialDelay = 1.seconds
  lazy val ophConfDir = Paths.get(
    ProjectRootFinder.findProjectRoot().getAbsolutePath,
    "src/test/resources/oph-configuration"
  )
  override val valintaTulosTimeout: FiniteDuration = 1.minute
  override val ytlSyncTimeout: Timeout = Timeout(1, SECONDS)
}
