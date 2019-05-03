package support

import java.util.Calendar
import java.util.concurrent.TimeUnit

import akka.util.Timeout
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import org.slf4j.LoggerFactory

import scala.concurrent.{Await, Future}

class DbArchiver(config: Config)(implicit val db: Database) extends Archiver {
  private val logger = LoggerFactory.getLogger(getClass)
  private implicit val timeout: Timeout = Timeout(30, TimeUnit.SECONDS)
  private val lockId = 44

  private def getEpoch(daysInThePast: Int): Long = {
    val day = Calendar.getInstance
    day.add(Calendar.DATE, - daysInThePast)
    day.getTime.getTime
  }

  private def run[T](f: Future[T]): T = Await.result(f, atMost = timeout.duration)

  override def archive() = {
    logger.info("Invoke archive scripts...")
    val batchSize = config.archiveBatchSize.toInt
    val oldest: Long = getEpoch(config.archiveNonCurrentAfterDays.toInt)
    val results = run(db.run(sql"""select
                     arkistoi_arvosana_deltat(${batchSize}, ${oldest}),
                     arkistoi_import_batch_deltat(${batchSize}, ${oldest}),
                     arkistoi_opiskelija_deltat(${batchSize}, ${oldest}),
                     arkistoi_opiskeluoikeus_deltat(${batchSize}, ${oldest}),
                     arkistoi_suoritus_deltat(${batchSize}, ${oldest})""".as[(Long, Long, Long, Long, Long)])).head
    logger.info(s"Archived rows (arvosana: ${results._1}, import_batch: ${results._2}, opiskelija: ${results._3}, opeskeluoikeus: ${results._4}, suoritus: ${results._5})")
  }

  override def acquireLockForArchiving(): Boolean = {
    run(db.run(sql"""select pg_try_advisory_lock(${lockId})""".as[Boolean])).head
  }

  override def clearLockForArchiving(): Boolean = {
    run(db.run(sql"""select pg_advisory_unlock(${lockId})""".as[Boolean])).head
  }
}
