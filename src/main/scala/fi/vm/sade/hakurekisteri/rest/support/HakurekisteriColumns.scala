package fi.vm.sade.hakurekisteri.rest.support

import org.joda.time.{LocalDate, DateTime}
import HakurekisteriDriver.simple._
import fi.vm.sade.hakurekisteri.suoritus.yksilollistaminen.Yksilollistetty
import fi.vm.sade.hakurekisteri.suoritus.yksilollistaminen

trait HakurekisteriColumns {
  implicit val datetimeLong = MappedColumnType.base[DateTime, Long](
    _.getMillis ,
    new DateTime(_)
  )

  implicit val localDateString = MappedColumnType.base[LocalDate, String](
    _.toString ,
    LocalDate.parse
  )

  implicit val yksilollistaminenString = MappedColumnType.base[Yksilollistetty, String](
    _.toString,
    yksilollistaminen.withName
  )

}

object HakurekisteriColumns extends HakurekisteriColumns
