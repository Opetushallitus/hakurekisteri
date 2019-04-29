package support

import java.util.Calendar
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.event.Logging
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._

import scala.concurrent.{Await, Future}
import scala.sys.process.stringToProcess

class DbArchiver(config: Config)(implicit val db: Database, implicit val system: ActorSystem) extends Archiver {
  private val logger = Logging.getLogger(system, this)
  private implicit val timeout: Timeout = Timeout(30, TimeUnit.SECONDS)
  private val lockId = 44

  private def getEpoch(daysInThePast: Int): Long = {
    val day = Calendar.getInstance
    day.add(Calendar.DATE, - daysInThePast)
    day.getTime.getTime
  }

  private def run[T](f: Future[T]): T = Await.result(f, atMost = timeout.duration)

  private def runCmdLineBlocking(command: String, failOnError: Boolean = true): Int = {
    val returnValue = command.!
    if (failOnError && returnValue != 0) {
      throw new RuntimeException(s"Command '$command' exited with $returnValue")
    }
    returnValue
  }

  override def archive(): Unit = {
    logger.info("About to invoke archive scripts")
    val oldest: Long = getEpoch(config.archiveNonCurrentAfterDays.toInt)
    val batchSize = config.archiveBatchSize.toInt
    runCmdLineBlocking(s"psql -h ${config.databaseHost} -p ${config.databasePort} -d suoritusrekisteri -v amount=${batchSize} -v oldest=${oldest} -f db-scripts/arkistoi_kaikki_deltat.sql")
  }

  override def acquireLockForArchiving(): Seq[Boolean] = {
    run(db.run(sql"""select pg_try_advisory_lock(${lockId})""".as[Boolean]))
  }

  override def clearLockForArchiving(): Seq[Boolean] = {
    run(db.run(sql"""select pg_advisory_unlock(${lockId})""".as[Boolean]))
  }
}
