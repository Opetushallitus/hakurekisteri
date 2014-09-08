package fi.vm.sade.hakurekisteri.opiskeluoikeus

import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriCommand
import org.joda.time.format.DateTimeFormat
import org.joda.time.{DateTime, LocalDate}
import org.json4s._
import org.scalatra.commands._
import org.scalatra.util.conversion.TypeConverter
import org.scalatra.{util, DefaultValue}

class CreateOpiskeluoikeusCommand extends HakurekisteriCommand[Opiskeluoikeus] {
  implicit def LocalDateDefaultValue: DefaultValue[LocalDate] = org.scalatra.DefaultValueMethods.default(new LocalDate(DateTime.now().getYear, 1, 1))

  import util.RicherString._
  implicit val stringToLocalDate: TypeConverter[String, LocalDate] = safeOption((in: String) => in.blankOption map DateTimeFormat.forPattern("dd.MM.yyyy").parseLocalDate)
  implicit val jsonToLocalDate: TypeConverter[JValue, LocalDate] = safeOption(_.extractOpt[LocalDate])

  val alkuPvm: Field[LocalDate] = asType[LocalDate]("alkuPvm").required
  val loppuPvm: Field[LocalDate] = asType[LocalDate]("loppuPvm").optional
  val henkiloOid: Field[String] = asType[String]("henkiloOid").notBlank
  val komo: Field[String] = asType[String]("komo").notBlank
  val myontaja: Field[String] = asType[String]("myontaja").notBlank

  override def toResource(user: String): Opiskeluoikeus = Opiskeluoikeus(alkuPvm.value.get, loppuPvm.value, henkiloOid.value.get, komo.value.get, myontaja.value.get, source = user)
}
