package fi.vm.sade.hakurekisteri.web.opiskeluoikeus

import fi.vm.sade.hakurekisteri.opiskeluoikeus.Opiskeluoikeus
import fi.vm.sade.hakurekisteri.web.rest.support.HakurekisteriCommand
import org.joda.time.format.DateTimeFormat
import org.joda.time.{DateTime, LocalDate}
import org.json4s._
import org.scalatra.commands._
import org.scalatra.util
import org.scalatra.util.conversion.TypeConverter

class CreateOpiskeluoikeusCommand extends HakurekisteriCommand[Opiskeluoikeus] {
  import util.RicherString._
  implicit val stringToLocalDate: TypeConverter[String, LocalDate] = safeOption((in: String) => in.blankOption map DateTimeFormat.forPattern("dd.MM.yyyy").parseLocalDate)
  implicit val jsonToLocalDate: TypeConverter[JValue, LocalDate] = safeOption(_.extractOpt[LocalDate])

  val alkuPvm: Field[String] = asType[String]("aika.alku").required
  val loppuPvm: Field[Option[String]] = asType[Option[String]]("aika.loppu").optional(None)
  val henkiloOid: Field[String] = asType[String]("henkiloOid").notBlank
  val komo: Field[String] = asType[String]("komo").notBlank
  val myontaja: Field[String] = asType[String]("myontaja").notBlank

  override def toResource(user: String): Opiskeluoikeus = Opiskeluoikeus(DateTime.parse(alkuPvm.value.get), loppuPvm.value.get.map(DateTime.parse), henkiloOid.value.get, komo.value.get, myontaja.value.get, source = user)
}
