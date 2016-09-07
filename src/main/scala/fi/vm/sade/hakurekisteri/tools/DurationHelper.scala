package fi.vm.sade.hakurekisteri.tools

import java.util.concurrent.TimeUnit

import org.joda.time.LocalDateTime

import scala.compat.Platform
import scala.concurrent.duration.{Duration, FiniteDuration}

object DurationHelper {
  def atTime(hour: Int = 0, minute: Int = 0, second: Int = 0): FiniteDuration = {
    val todayAtTime = new LocalDateTime().withTime(hour, minute, second, 0)
    todayAtTime match {
      case d if d.isBefore(new LocalDateTime()) => Duration(d.plusDays(1).toDate.getTime - Platform.currentTime, TimeUnit.MILLISECONDS)
      case d => Duration(d.toDate.getTime - Platform.currentTime, TimeUnit.MILLISECONDS)
    }
  }
}
