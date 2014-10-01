package fi.vm.sade.hakurekisteri.rest.support

import org.joda.time.DateTime
import scala.slick.driver.JdbcDriver.simple._

object HakurekisteriColumns {
  implicit val datetimeLong = MappedColumnType.base[DateTime, Long](
    _.getMillis ,    // map Bool to Int
    new DateTime(_)  // map Int to Bool
  )

}
