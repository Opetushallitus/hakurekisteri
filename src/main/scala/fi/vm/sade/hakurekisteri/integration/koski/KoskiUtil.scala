package fi.vm.sade.hakurekisteri.integration.koski

import java.util.Calendar

import org.joda.time.LocalDate

object KoskiUtil {
  //TODO: Vaihtuu vuosittain
  def parseNextThirdOfJune(): LocalDate = {
    var cal = java.util.Calendar.getInstance()
    cal.set(cal.get(Calendar.YEAR), 5, 3)
    var now = LocalDate.now()
    var thirdOfJune = LocalDate.fromCalendarFields(cal)
    if(now.isAfter(thirdOfJune)){
      thirdOfJune.plusYears(1)
    }
    thirdOfJune
  }

}
