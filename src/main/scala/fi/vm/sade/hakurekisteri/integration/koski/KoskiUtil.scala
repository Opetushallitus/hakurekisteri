package fi.vm.sade.hakurekisteri.integration.koski

import java.util.Calendar

import fi.vm.sade.hakurekisteri.integration.OphUrlProperties
import org.joda.time.LocalDate

object KoskiUtil {

  val koski_integration_source = "koski"
  var deadlineDate: LocalDate = new LocalDate(OphUrlProperties.getProperty("suoritusrekisteri.koski.deadline.date"))
  def arvosanatWithNelosiaDate(): LocalDate = {
    deadlineDate.minusDays(14)
  }
}
