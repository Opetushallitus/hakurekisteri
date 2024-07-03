package fi.vm.sade.hakurekisteri.ovara

import fi.vm.sade.utils.slf4j.Logging

import java.time.format.DateTimeFormatter
import java.time.LocalTime

class OvaraUtil extends Logging {
  def shouldFormEnsikertalaiset() = {
    val formatter = DateTimeFormatter.ofPattern("HH")
    val timeNow = LocalTime.now

    val hoursString = timeNow.format(formatter)
    val hours = Integer.parseInt(hoursString)
    val shouldForm = 0.equals(hours)
    logger.info(s"Time now: $timeNow, hours: $hours, should form ensikertalaiset: $shouldForm")
    shouldForm
  }
}

object OvaraUtil extends OvaraUtil
