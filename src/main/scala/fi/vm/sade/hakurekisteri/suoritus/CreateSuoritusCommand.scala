package fi.vm.sade.hakurekisteri.suoritus

import org.scalatra.commands._
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriCommand
import org.joda.time.{LocalDate, DateTime}
import fi.vm.sade.hakurekisteri.suoritus.yksilollistaminen.Yksilollistetty
import yksilollistaminen.Ei
import org.scalatra.{util, DefaultValue}
import org.scalatra.util.conversion.TypeConverter
import org.joda.time.format.DateTimeFormat
import org.json4s.JValue


class CreateSuoritusCommand extends HakurekisteriCommand[Suoritus] {

  implicit def LocalDateDefaultValue: DefaultValue[LocalDate] = org.scalatra.DefaultValueMethods.default(new LocalDate(DateTime.now().getYear,1,1))
  implicit def YksilollistaminenDefaultValue: DefaultValue[Yksilollistetty] = org.scalatra.DefaultValueMethods.default(Ei)


  import util.RicherString._
  implicit val stringToLocalDate: TypeConverter[String, LocalDate] = safeOption((in:String)=>in.blankOption map DateTimeFormat.forPattern("dd.MM.yyyy").parseLocalDate)
  implicit val jsonToLocalDate: TypeConverter[JValue, LocalDate] = safeOption(_.extractOpt[LocalDate])

  implicit val stringToYksilollistaminen: TypeConverter[String, Yksilollistetty] = safeOption(_.blankOption.map (yksilollistaminen.withName))
  implicit val jsonToYksilollistaminen: TypeConverter[JValue, Yksilollistetty] = safeOption(_.extractOpt[Yksilollistetty])


  val komotooid: Field[String] = asType[String]("komoto.oid").notBlank
  val komo: Field[String] = asType[String]("komoto.komo").notBlank
  val tarjoaja: Field[String] = asType[String]("komoto.tarjoaja").notBlank

  val tila: Field[String] = asType[String]("tila").notBlank
  val valmistuminen: Field[LocalDate] = asType[LocalDate]("valmistuminen").required
  val henkiloOid: Field[String]  = asType[String]("henkiloOid").notBlank
  val yks: Field[Yksilollistetty]  = asType[Yksilollistetty]("yksilollistaminen")
  val oppilaitosOid: Field[String]  = asType[String]("oppilaitosOid")
  val suoritusKieli: Field[String] = asType[String]("suoritusKieli")

  override def toResource: Suoritus = Suoritus(Komoto(komotooid.value.get, komo.value.get, tarjoaja.value.get), tila.value.get, valmistuminen.value.get, henkiloOid.value.get, yks.value.get, suoritusKieli.value.get)
}


