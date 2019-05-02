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

  override def archive(): Unit = {
    logger.info("About to invoke archive scripts")
    val batchSize = config.archiveBatchSize.toInt
    val oldest: Long = getEpoch(config.archiveNonCurrentAfterDays.toInt)
    val results = run(db.run(sql"""select
                     arkistoi_arvosana_deltat(${batchSize}, ${oldest}),
                     arkistoi_import_batch_deltat(${batchSize}, ${oldest}),
                     arkistoi_opiskelija_deltat(${batchSize}, ${oldest}),
                     arkistoi_opiskeluoikeus_deltat(${batchSize}, ${oldest}),
                     arkistoi_suoritus_deltat(${batchSize}, ${oldest})""".as[(Long, Long, Long, Long, Long)]))
    logger.info(s"Archived (arvosana, import_batch, opiskelija, opeskeluoikeus, suoritus): $results")
  }

  override def acquireLockForArchiving(): Seq[Boolean] = {
    run(db.run(sql"""select pg_try_advisory_lock(${lockId})""".as[Boolean]))
  }

  override def clearLockForArchiving(): Seq[Boolean] = {
    run(db.run(sql"""select pg_advisory_unlock(${lockId})""".as[Boolean]))
  }
}
