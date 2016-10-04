package fi.vm.sade.hakurekisteri.tools

import fi.vm.sade.hakurekisteri.tools.LambdaJob.LamdaFunction
import org.quartz._
import org.quartz.JobBuilder._
import collection.JavaConverters._



case class LambdaRunnable(l: LamdaFunction)

object LambdaJob {
  type LamdaFunction = () => Unit

  def scheduleLambdaJob(s: Scheduler, t: Trigger, l: LamdaFunction) {
    val dataMap = new JobDataMap()
    dataMap.put(classOf[LambdaJob].getSimpleName, LambdaRunnable(l))
    val job = newJob(classOf[LambdaJob]).setJobData(dataMap).build()
    s.scheduleJob(job, t);
  }
}

class LambdaJob extends Job {

  override def execute(context: JobExecutionContext): Unit = {
    context.getJobDetail().getJobDataMap().values().asScala.foreach {
      case l: LambdaRunnable => l.l()
      case _ =>
    }
  }

}
