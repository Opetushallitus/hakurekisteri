package fi.vm.sade.hakurekisteri.tools

import java.io.File
import java.nio.file.Files

import fi.vm.sade.utils.slf4j.Logging
import fi.vm.sade.utils.tcp.{ChooseFreePort, PortChooser}
import org.apache.commons.io.FileUtils
import org.slf4j.LoggerFactory

import scala.sys.process.stringToProcess

object ItPostgres extends Logging {

  val portChooser = new ChooseFreePort()
  val port = portChooser.chosenPort
  val dataDirName = s"suoritusrekisteri-it-db/db-instance/"
  val dbName = "suoritusrekisteri"
  val startStopRetries = 100
  val startStopRetryIntervalMillis = 100
  private val dataDirFile = new File(dataDirName)
  val dataDirPath = dataDirFile.getAbsolutePath
  lazy val log = LoggerFactory.getLogger(getClass)

  if (!dataDirFile.isDirectory) {
    log.info(s"PostgreSQL data directory $dataDirPath does not exist, initing new database there.")
    Files.createDirectories(dataDirFile.toPath)
    runBlocking(s"chmod 0700 $dataDirPath")
    runBlocking(s"initdb -D $dataDirPath")
  }
  log.info(s"Using PostgreSQL in port $port with data directory $dataDirPath")

  private def isAcceptingConnections(): Boolean = {
    runBlocking(s"pg_isready -q -t 1 -h localhost -p $port -d $dbName", failOnError = false) == 0
  }

  private def readPid: Option[Int] = {
    val pidFile = new File(dataDirFile, "postmaster.pid")
    if (!pidFile.canRead) {
      None
    } else {
      Some(FileUtils.readFileToString(pidFile).split("\n")(0).toInt)
    }
  }

  private def tryTimes(times: Int, sleep: Int)(thunk: () => Boolean): Boolean = {
    times match {
      case n if n < 1 => false
      case 1 => thunk()
      case n => thunk() || { Thread.sleep(sleep); tryTimes(n - 1, sleep)(thunk) }
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
    readPid match {
      case Some(pid) => {
        println(s"PostgreSQL pid $pid is found in pid file, not touching the database.")
      }
      case None => {
        println(s"PostgreSQL pid file cannot be read, starting in port $port:")
        s"postgres --config_file=postgresql/postgresql.conf -d 0 -D $dataDirPath -p $port".run()
        if (!tryTimes(startStopRetries, startStopRetryIntervalMillis)(isAcceptingConnections)) {
          throw new RuntimeException(s"postgres not accepting connections in port $port after $startStopRetries attempts with $startStopRetryIntervalMillis ms intervals")
        }
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
    println("Resetting database tables ...")
    runBlocking(s"psql -h localhost -p $port -d $dbName -f postgresql/reset_database.sql")
  }

  def stop() {
    readPid match {
      case Some(pid) => {
        println(s"Killing PostgreSQL process $pid")
        runBlocking(s"kill -s SIGINT $pid")
        if (!tryTimes(startStopRetries, startStopRetryIntervalMillis)(() => readPid.isEmpty)) {
          println(s"postgres in pid $pid did not stop gracefully after $startStopRetries attempts with $startStopRetryIntervalMillis ms intervals")
        }
      }
      case None => log.info("No PostgreSQL pid found, not trying to stop it.")
    }
    if (dataDirFile.exists()) {
      println(s"Nuking PostgreSQL data directory $dataDirPath")
      FileUtils.forceDelete(dataDirFile)
    }
  }

  def getEndpointURL: String = {
    start()
    s"jdbc:postgresql://localhost:$port/suoritusrekisteri"
  }
}
