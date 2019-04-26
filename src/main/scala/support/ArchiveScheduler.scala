package support

import java.util.Calendar

import akka.actor.ActorSystem
import akka.event.Logging
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.tools.LambdaJob.lambdaJob
import org.quartz.CronScheduleBuilder.cronSchedule
import org.quartz.TriggerBuilder.newTrigger
import org.quartz.impl.StdSchedulerFactory

import scala.sys.process.stringToProcess

class ArchiveScheduler(config: Config)(implicit val system: ActorSystem) {
  private val logger = Logging.getLogger(system, this)

  def start(dbArchiveCronExpression: String): Unit = {
    if (dbArchiveCronExpression == null) {
      logger.warning("Archive cron expression not specified, archive job will not be scheduled.")
      return
    }
    val quartzScheduler = StdSchedulerFactory.getDefaultScheduler()
    if (!quartzScheduler.isStarted) {
      quartzScheduler.start()
    }

    quartzScheduler.scheduleJob(lambdaJob(archive()),
      newTrigger().startNow().withSchedule(cronSchedule(dbArchiveCronExpression)).build());
  }

  def archive(): () => Unit = { () => {
    def getEpoch(daysInThePast: Int): Long = {
      val day = Calendar.getInstance
      day.add(Calendar.DATE, - daysInThePast)
      day.getTime.getTime
    }

    logger.info("About to invoke archive scripts")
      val oldest: Long = getEpoch(config.archiveNonCurrentAfterDays.toInt)
      val batchSize = config.archiveBatchSize.toInt
      runBlocking(s"psql -h ${config.databaseHost} -p ${config.databasePort} -d suoritusrekisteri -v amount=${batchSize} -v oldest=${oldest} -f db-scripts/arkistoi_kaikki_deltat.sql")
    }
  }

  private def runBlocking(command: String, failOnError: Boolean = true): Int = {
    val returnValue = command.!
    if (failOnError && returnValue != 0) {
      throw new RuntimeException(s"Command '$command' exited with $returnValue")
    }
    returnValue
  }

}
