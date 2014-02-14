package fi.vm.sade.hakurekisteri.rest.support

import org.json4s.CustomSerializer
import java.util.UUID
import org.json4s.JsonAST.JString
import org.joda.time.LocalDate
import org.joda.time.format.{DateTimeFormat, DateTimeFormatter}
import scala.util.Try


class LocalDateSerializer(dayFormat:String = "dd.MM.yyyy") extends CustomSerializer[LocalDate](format => (
  {
    case JString(date) =>
      Try(format.dateFormat.parse(date)).map(new LocalDate(_)).
      recoverWith{case _:Exception => Try(DateTimeFormat.forPattern(dayFormat).parseLocalDate(date))}
        .get


  },
  {
    case x: LocalDate =>
      JString(x.toString(dayFormat))
  }
  ))
