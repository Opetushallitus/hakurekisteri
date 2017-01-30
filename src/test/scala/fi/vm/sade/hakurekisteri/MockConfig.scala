package fi.vm.sade.hakurekisteri

import java.nio.file.Paths

import fi.vm.sade.hakurekisteri.tools.ItPostgres

import scala.concurrent.duration._

class MockConfig extends Config {
  def mockMode = true
  log.info("Using mock config")
  val dbPort = ItPostgres.port
  override val databaseUrl = s"jdbc:postgresql://localhost:$dbPort/suoritusrekisteri"
  override val postgresUser = null //properties.getOrElse("suoritusrekisteri.db.user", "postgres")
  override val postgresPassword = null //properties.getOrElse("suoritusrekisteri.db.password", "postgres")
  override val slowQuery: Long = 200
  override val reallySlowQuery: Long = 10000
  override val importBatchProcessingInitialDelay = 1.seconds
  lazy val ophConfDir = Paths.get(ProjectRootFinder.findProjectRoot().getAbsolutePath, "src/test/resources/oph-configuration")
}
