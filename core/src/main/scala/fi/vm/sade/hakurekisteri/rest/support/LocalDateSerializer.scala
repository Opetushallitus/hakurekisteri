package fi.vm.sade.hakurekisteri.rest.support

import org.json4s.CustomSerializer
import java.util.UUID
import org.json4s.JsonAST.JString
import org.joda.time.LocalDate
import org.joda.time.format.{DateTimeFormat, DateTimeFormatter}
import scala.util.Try


class LocalDateSerializer(dayFormat:String = LocalDateSerializer.dayFormat) extends CustomSerializer[LocalDate](format => (
  {
    case JString(date) =>
      format.dateFormat.parse(date).map(new LocalDate(_)).getOrElse(
        Try(DateTimeFormat.forPattern(dayFormat).parseLocalDate(date)).get)}



  ,
  {
    case x: LocalDate =>
      JString(x.toString(dayFormat))
  }
  ))


object LocalDateSerializer {
  val dayFormat = "dd.MM.yyyy"
}