package fi.vm.sade.hakurekisteri.tools

import java.io.File
import java.nio.file.Files

import fi.vm.sade.utils.slf4j.Logging
import fi.vm.sade.utils.tcp.ChooseFreePort
import org.apache.commons.io.FileUtils
import org.slf4j.{Logger, LoggerFactory}

import scala.sys.process.stringToProcess

object ItPostgres extends Logging {

  val alreadyRunningPort: Option[Int] = getAlreadyRunningPostgresPort()
  val portChooser = new ChooseFreePort()
  val generatedPort: Int = portChooser.chosenPort
  val port: Int = alreadyRunningPort.getOrElse(portChooser.chosenPort)
  val dataDirName = s"suoritusrekisteri-it-db/db-instance/"
  val dbName = "suoritusrekisteri"
  val startStopRetries = 100
  val startStopRetryIntervalMillis = 100
  private val dataDirFile = new File(dataDirName)
  val dataDirPath: String = dataDirFile.getAbsolutePath
  lazy val log: Logger = LoggerFactory.getLogger(getClass)

  if (alreadyRunningPort.nonEmpty) {
    log.info(s"PostgreSQL is already running in port $port")
  } else {
    log.info(s"PostgreSQL use own instance")
    if (!dataDirFile.isDirectory) {
      log.info(
        s"PostgreSQL data directory $dataDirPath does not exist, initing new database there."
      )
      Files.createDirectories(dataDirFile.toPath)
      runBlocking(s"chmod 0700 $dataDirPath")
      runBlocking(s"initdb -D $dataDirPath --no-locale")
    } else {
      log.info(
        s"PostgreSQL data directory $dataDirPath exists, assuming that database is there and initialized."
      )
    }
    log.info(s"Using PostgreSQL in port $port with data directory $dataDirPath")
  }

  private def getAlreadyRunningPostgresPort(): Option[Int] = {
    val port = System.getProperty("postgresRunningOnPort")
    if (port != null && port != "null") {
      Some(port.toInt)
    } else {
      None
    }
  }

  private def isAcceptingConnections(): Boolean = {
    runBlocking(s"pg_isready -q -t 1 -h localhost -p $port -d $dbName", failOnError = false) == 0
  }

  private def readPid: Option[Int] = {
    val pidFile = new File(dataDirFile, "postmaster.pid")
    log.info(s"Reading pid from $pidFile")
    if (!pidFile.canRead) {
      None
    } else {
      Some(FileUtils.readFileToString(pidFile, "UTF-8").split("\n")(0).toInt)
    }
  }

  private def tryTimes(times: Int, sleep: Int)(thunk: () => Boolean): Boolean = {
    times match {
      case n if n < 1 => false
      case 1          => thunk()
      case n          => thunk() || { Thread.sleep(sleep); tryTimes(n - 1, sleep)(thunk) }
    }
  }

  private def runBlocking(command: String, failOnError: Boolean = true): Int = {
    val returnValue = command.!
    if (failOnError && returnValue != 0) {
      throw new RuntimeException(s"Command '$command' exited with $returnValue")
    }
    returnValue
  }

  def start() {
    if (alreadyRunningPort.isEmpty) {
      readPid match {
        case Some(pid) =>
          log.info(s"PostgreSQL pid $pid is found in pid file, not touching the database.")
        case None =>
          log.info(s"PostgreSQL pid file cannot be read, starting in port $port:")
          s"postgres --config_file=postgresql/postgresql.conf -d 0 -D $dataDirPath -p $port".run()
          if (
            !tryTimes(startStopRetries, startStopRetryIntervalMillis)(() =>
              ItPostgres.this.isAcceptingConnections()
            )
          ) {
            throw new RuntimeException(
              s"postgres not accepting connections in port $port after $startStopRetries attempts with $startStopRetryIntervalMillis ms intervals"
            )
          }
          log.info(s"PostgreSQL started in port $port , (re)creating database $dbName")
          runBlocking(s"dropdb -p $port --if-exists $dbName")
          runBlocking(s"createdb -p $port $dbName")
          runBlocking(s"psql -h localhost -p $port -d $dbName -f postgresql/init_it_postgresql.sql")

          Runtime.getRuntime.addShutdownHook(new Thread(new Runnable {
            override def run() {
              stop()
            }
          }))
      }
    }
  }

  def reset(): Unit = {
    log.info("Resetting database tables ...")
    runBlocking(s"psql -h localhost -p $port -d $dbName -f postgresql/reset_database.sql")
  }

  def stop() {
    if (alreadyRunningPort.isEmpty) {
      readPid match {
        case Some(pid) => {
          log.info(s"Killing PostgreSQL process $pid")
          runBlocking(s"kill -s SIGINT $pid")
          if (!tryTimes(startStopRetries, startStopRetryIntervalMillis)(() => readPid.isEmpty)) {
            log.warn(
              s"postgres in pid $pid did not stop gracefully after $startStopRetries attempts with $startStopRetryIntervalMillis ms intervals"
            )
          }
        }
        case None => log.info("No PostgreSQL pid found, not trying to stop it.")
      }
      if (dataDirFile.exists()) {
        log.info(s"Nuking PostgreSQL data directory $dataDirPath")
        FileUtils.forceDelete(dataDirFile)
      }
    } else {
      log.info(s"PostgreSQL was already running, let it be")
    }
  }

  def getEndpointURL: String = {
    start()
    s"jdbc:postgresql://localhost:$port/suoritusrekisteri"
  }
}
