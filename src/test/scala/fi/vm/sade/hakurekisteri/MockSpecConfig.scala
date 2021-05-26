package fi.vm.sade.hakurekisteri

import akka.util.Timeout
import com.dimafeng.testcontainers.PostgreSQLContainer
import support.SureDbLoggingConfig

import java.nio.file.Paths
import scala.concurrent.duration._

class MockSpecConfig(container: PostgreSQLContainer) extends Config {
  def mockMode = true
  log.info("Using mock config")
  val dbPort = container.exposedPorts.head
  override val databaseUrl = container.jdbcUrl
  override val databaseHost = "localhost"
  override val databasePort = dbPort.toString
  override val postgresUser = container.username
  override val postgresPassword = container.password
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
