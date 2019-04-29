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

class ArchiveScheduler(archiver: Archiver)(implicit val system: ActorSystem) {
  private val logger = Logging.getLogger(system, this)

  def start(archiveCronExpression: String): Unit = {
    if (archiveCronExpression == null) {
      logger.warning("Archive cronjob expression not specified, scheduler not created.")
      return
    }
    val quartzScheduler = StdSchedulerFactory.getDefaultScheduler()
    if (!quartzScheduler.isStarted) {
      quartzScheduler.start()
    }

    quartzScheduler.scheduleJob(lambdaJob(archive()),
      newTrigger().startNow().withSchedule(cronSchedule(archiveCronExpression)).build());
  }

  def archive(): () => Unit = { () => {
      logger.info("About to invoke archive scripts")
      if (archiver.acquireLockForArchiving().head) {
        logger.info("Archiving...")
        archiver.archive()
        archiver.clearLockForArchiving()
      }
      else {
        logger.info("Did not acquire lock, most probably some other instance is right now archiving")
      }
    }
  }
}

