package fi.vm.sade.hakurekisteri.tools

import akka.util.Timeout
import fi.vm.sade.utils.slf4j.Logging
import org.slf4j.{Logger, LoggerFactory}
import org.testcontainers.postgresql.PostgreSQLContainer
import slick.jdbc.JdbcBackend
import slick.jdbc.JdbcBackend.Database
import slick.jdbc.PostgresProfile.api._

import java.util.concurrent.TimeUnit
import scala.concurrent.{Await, Future}
import scala.collection.JavaConverters._

object ItPostgres extends Logging {
  private def asyncExecutor = AsyncExecutor.default("slick-executor", 20)
  val container: PostgreSQLContainer =
    new PostgreSQLContainer("postgres:15")
      .withDatabaseName("suoritusrekisteri")
      .withPrivilegedMode(true)
      .withInitScript("database/init.sql")
  container.setPortBindings(List("55432:5432").asJava)
  container.start()
  private val timeout: Timeout = Timeout(30, TimeUnit.SECONDS)
  var port: Int = 55432
  lazy val log: Logger = LoggerFactory.getLogger(getClass)
  private var running = true

  def start(): Unit = {
    if (!running) {
      container.start()
      port = 55432
      running = true
    }
  }

  def reset(): Unit = {
    log.info("Resetting database tables ...")
    val db = Database.forURL(
      url = container.getJdbcUrl,
      user = container.getUsername,
      password = container.getPassword,
      executor = asyncExecutor
    )
    val tablesTruncate = runAwait(
      db.run(
        sql"select tablename from pg_catalog.pg_tables WHERE schemaname != 'information_schema' AND schemaname != 'pg_catalog' AND tablename != 'flyway_schema_history' AND tablename != 'siirtotiedosto'"
          .as[String]
      )
    )
    tablesTruncate.foreach(tableToTruncate => runAwait(db.run(sqlu"truncate #$tableToTruncate")))
    db.close()
  }

  def stop(): Unit = {
    container.stop()
    running = false
  }

  def getEndpointURL: String = {
    start()
    container.getJdbcUrl
  }

  def getDatabase: JdbcBackend.DatabaseDef = {
    Database.forURL(
      url = container.getJdbcUrl,
      user = container.getUsername,
      password = container.getPassword,
      executor = asyncExecutor
    )
  }

  private def runAwait[T](f: Future[T]): T = Await.result(f, atMost = timeout.duration)
}
