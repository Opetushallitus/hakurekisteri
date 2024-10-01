package fi.vm.sade.hakurekisteri.integration.ytl

import org.quartz.CronScheduleBuilder._
import org.quartz.TriggerBuilder._

case class HourAndMinute(hour: Int, minute: Int) {

  override def toString = {
    f"$hour%02d:$minute%02d"
  }

  def asTrigger = newTrigger()
    .startNow()
    .withSchedule(dailyAtHourAndMinute(hour, minute))
    .build()
}

object HourAndMinute {
  private val hourAndMinute = """(\d\d):(\d\d)""".r

  def apply(hourAndMinuteSpec: String): HourAndMinute = hourAndMinuteSpec match {
    case hourAndMinute(hour, minute) =>
      HourAndMinute.apply(Integer.parseInt(hour), Integer.parseInt(minute))
    case s =>
      throw new IllegalArgumentException(
        s"Illegal hourAndMinute specification '$s'. Expected format i.e. 01:00"
      )
  }

}
