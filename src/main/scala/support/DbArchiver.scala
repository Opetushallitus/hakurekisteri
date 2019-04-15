package support

import akka.actor.ActorSystem
import akka.event.Logging
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.tools.LambdaJob.lambdaJob
import org.quartz.CronScheduleBuilder.cronSchedule
import org.quartz.TriggerBuilder.newTrigger
import org.quartz.impl.StdSchedulerFactory

import scala.sys.process.stringToProcess

class DbArchiver(config: Config)(implicit val system: ActorSystem) {
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
      logger.info("About to invoke archive scripts")
      runBlocking("pwd")
      runBlocking(s"psql -h ${config.databaseHost} -p ${config.databasePort} -d suoritusrekisteri -v amount=234 -f db-scripts/arkistoi_kaikki_deltat.sql")
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
