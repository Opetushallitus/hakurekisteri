package fi.vm.sade.hakurekisteri.rest.support

import org.joda.time.{LocalDate, DateTime}
import HakurekisteriDriver.simple._
import fi.vm.sade.hakurekisteri.suoritus.yksilollistaminen.Yksilollistetty
import fi.vm.sade.hakurekisteri.suoritus.yksilollistaminen
import org.json4s.JValue
import org.json4s.JsonDSL._

trait HakurekisteriColumns extends HakurekisteriJsonSupport {
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

  implicit val jsonMap = MappedColumnType.base[Map[String, String], JValue](
    map => map2jvalue(map),
    _.extractOpt[Map[String, String]].getOrElse(Map())
  )

}

object HakurekisteriColumns extends HakurekisteriColumns
