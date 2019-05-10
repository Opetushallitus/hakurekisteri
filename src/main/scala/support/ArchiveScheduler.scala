package support

import fi.vm.sade.hakurekisteri.tools.LambdaJob.lambdaJob
import org.quartz.CronScheduleBuilder.cronSchedule
import org.quartz.TriggerBuilder.newTrigger
import org.quartz.impl.StdSchedulerFactory
import org.slf4j.LoggerFactory

class ArchiveScheduler(archiver: Archiver) {
  private val logger = LoggerFactory.getLogger(getClass)

  def start(archiveCronExpression: String): Unit = {
    if (archiveCronExpression == null) {
      logger.warn("Archive cronjob expression not specified, scheduler not created.")
      throw new RuntimeException("Archive cronjob expression not specified, scheduler not created.")
    }
    val quartzScheduler = StdSchedulerFactory.getDefaultScheduler()
    if (!quartzScheduler.isStarted) {
      quartzScheduler.start()
    }

    quartzScheduler.scheduleJob(lambdaJob(archive()),
      newTrigger().startNow().withSchedule(cronSchedule(archiveCronExpression)).build());
    logger.info(s"ArchiveScheduler started, cron expression=$archiveCronExpression")
  }

  def archive(): () => Unit = { () => {
      if (archiver.acquireLockForArchiving()) {
        logger.info("Archive lock acquired, archiving...")
        try {
          archiver.archive()
        }
        finally {
          archiver.clearLockForArchiving()
          logger.info("Archive lock released.")
        }
      }
      else {
        logger.info("Did not acquire archive lock, most probably some other instance is right now archiving")
      }
    }
  }

}

