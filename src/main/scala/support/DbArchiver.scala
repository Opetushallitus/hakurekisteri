package support

import java.util.Calendar
import java.util.concurrent.TimeUnit

import akka.util.Timeout
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import org.slf4j.LoggerFactory

import scala.collection.immutable.SortedMap
import scala.concurrent.{Await, Future}

class DbArchiver(config: Config)(implicit val db: Database) extends Archiver {
  private val logger = LoggerFactory.getLogger(getClass)
  private implicit val timeout: Timeout = Timeout(500, TimeUnit.SECONDS)
  private val lockId = 44

  private def getEpoch(daysInThePast: Int): Long = {
    val day = Calendar.getInstance
    day.add(Calendar.DATE, - daysInThePast)
    day.getTime.getTime
  }

  private def run[T](f: Future[T]): T = Await.result(f, atMost = timeout.duration)

  type BatchStatistics = Map[String, Long]

  private def logStatistics(batchStatistics: BatchStatistics, message: String) = {
    val sortedKeys: List[String] = batchStatistics.keys.toList.sortWith(_ < _)
    logger.info(message + " (" + sortedKeys.map(t => t + ": " + batchStatistics(t)).mkString(", ") + ")")
  }

  override def archive() = {
    logger.info("Invoke archive scripts...")
    val startTime = System.nanoTime()
    def elapsedTimeMinutes: Long = TimeUnit.NANOSECONDS.toMinutes(System.nanoTime() - startTime)
    var statisticsTotal: BatchStatistics = Map()
    var batchStatistics: BatchStatistics = Map()
    def isAnythingDoneInLastBatch: Boolean = batchStatistics.exists(_._2 > 0)
    def newStatisticsTotal(currentTotal: BatchStatistics, batch: BatchStatistics): BatchStatistics = {
      batch.keys.map(k => k -> (batch(k) + currentTotal.getOrElse(k, 0l))).toMap
    }
    do {
      try {
        batchStatistics = archiveBatch()
        if (isAnythingDoneInLastBatch) {
          logStatistics(batchStatistics, "Archived batch rows")
          statisticsTotal = newStatisticsTotal(statisticsTotal, batchStatistics)
          logStatistics(statisticsTotal, "Archived TOTAL rows")
        }
      }
      catch {
        case e: Throwable => logger.warn("Archive batch failed: ", e)
      }
    } while(elapsedTimeMinutes < 180 && isAnythingDoneInLastBatch)
    if (isAnythingDoneInLastBatch) logger.warn("Archiving is stopped because 3h timeout has expired.")
    else logger.info("Archiving completed")
  }

  private def archiveBatch(): BatchStatistics = {
    val batchSize = config.archiveBatchSize.toInt
    val oldest: Long = getEpoch(config.archiveNonCurrentAfterDays.toInt)
    val tableNames: Seq[String] = Seq("arvosana", "import_batch", "opiskelija", "opiskeluoikeus", "suoritus")
    val batchStatistics: BatchStatistics = tableNames.map(tableName => {
      val archivedRows = run(db.run(sql"""select arkistoi_#${tableName}_deltat(${batchSize}, ${oldest})""".as[(Long)])).head
      tableName -> archivedRows
    }).toMap
    batchStatistics
  }

  override def acquireLockForArchiving(): Boolean = {
    run(db.run(sql"""select pg_try_advisory_lock(${lockId})""".as[Boolean])).head
  }

  override def clearLockForArchiving(): Boolean = {
    run(db.run(sql"""select pg_advisory_unlock(${lockId})""".as[Boolean])).head
  }
}
