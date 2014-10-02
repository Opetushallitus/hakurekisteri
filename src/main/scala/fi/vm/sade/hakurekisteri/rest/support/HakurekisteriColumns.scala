package fi.vm.sade.hakurekisteri.rest.support

import org.joda.time.{LocalDate, DateTime}
import scala.slick.driver.JdbcDriver.simple._

object HakurekisteriColumns {
  implicit val datetimeLong = MappedColumnType.base[DateTime, Long](
    _.getMillis ,
    new DateTime(_)
  )

  implicit val localDateString = MappedColumnType.base[LocalDate, String](
    _.toString ,
    LocalDate.parse
  )

}
