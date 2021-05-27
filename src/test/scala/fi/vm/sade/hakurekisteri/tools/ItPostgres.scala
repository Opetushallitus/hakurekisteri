package fi.vm.sade.hakurekisteri.tools

import akka.util.Timeout
import fi.vm.sade.utils.slf4j.Logging
import org.slf4j.{Logger, LoggerFactory}
import com.dimafeng.testcontainers.PostgreSQLContainer
import slick.jdbc.JdbcBackend
import slick.jdbc.JdbcBackend.Database
import slick.jdbc.PostgresProfile.api._

import java.util.concurrent.TimeUnit
import scala.concurrent.{Await, Future}

object ItPostgres extends Logging {

  val container: PostgreSQLContainer =
    PostgreSQLContainer.Def(databaseName = "suoritusrekisteri").createContainer()
  container.configure { c =>
    c.withPrivilegedMode(true)
    c.withInitScript("database/init.sql")
  }
  private val timeout: Timeout = Timeout(30, TimeUnit.SECONDS)
  container.start()
  var port: Int = getPortNumber()
  lazy val log: Logger = LoggerFactory.getLogger(getClass)
  private var running = true

  private def getPortNumber(): Int = {
    container.jdbcUrl.split("jdbc:postgresql://localhost:").last.split("\\/").head.toInt
  }

  def start(): Unit = {
    if (!running) {
      container.start()
      port = getPortNumber()
      running = true
    }
  }

  def reset(): Unit = {
    log.info("Resetting database tables ...")
    val db = Database.forURL(container.jdbcUrl, container.username, container.password)
    val tablesTruncate = runAwait(
      db.run(
        sql"select tablename from pg_catalog.pg_tables WHERE schemaname != 'information_schema' AND schemaname != 'pg_catalog' AND tablename != 'flyway_schema_history'"
          .as[String]
      )
    )
    tablesTruncate.foreach(tableToTruncate => runAwait(db.run(sqlu"truncate #$tableToTruncate")))
    db.close()
  }

  def stop() {
    container.stop()
    running = false
  }

  def getEndpointURL: String = {
    start()
    container.jdbcUrl
  }

  def getDatabase: JdbcBackend.DatabaseDef = {
    Database.forURL(container.jdbcUrl, container.username, container.password)
  }

  private def runAwait[T](f: Future[T]): T = Await.result(f, atMost = timeout.duration)
}
