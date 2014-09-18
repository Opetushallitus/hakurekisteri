package fi.vm.sade.hakurekisteri.rest.support

import org.scalatra.commands.JsonCommand
import org.scalatra.{DefaultValue, util}
import org.joda.time.{DateTime, LocalDate}
import org.scalatra.util.conversion.TypeConverter
import org.joda.time.format.DateTimeFormat
import org.json4s._

trait LocalDateSupport { this: JsonCommand =>
  import util.RicherString._


  implicit def LocalDateDefaultValue: DefaultValue[LocalDate] = org.scalatra.DefaultValueMethods.default(new LocalDate(DateTime.now().getYear,1,1))


  implicit val stringToLocalDate: TypeConverter[String, LocalDate] = safeOption((in:String)=>in.blankOption map DateTimeFormat.forPattern("dd.MM.yyyy").parseLocalDate)
  implicit val jsonToLocalDate: TypeConverter[JValue, LocalDate] = safeOption(_.extractOpt[LocalDate])

}
