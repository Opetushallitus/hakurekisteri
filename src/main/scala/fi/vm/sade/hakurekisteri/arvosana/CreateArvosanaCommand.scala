package fi.vm.sade.hakurekisteri.arvosana

import org.scalatra.commands._
import fi.vm.sade.hakurekisteri.rest.support.{LocalDateSupport, HakurekisteriCommand}
import java.util.UUID
import org.joda.time.LocalDate
import org.scalatra.util.conversion.TypeConverter
import org.json4s._
import fi.vm.sade.hakurekisteri.suoritus.yksilollistaminen._
import org.scalatra.{util, DefaultValue}
import fi.vm.sade.hakurekisteri.suoritus.yksilollistaminen


class CreateArvosanaCommand extends HakurekisteriCommand[Arvosana] with LocalDateSupport {

  implicit def OptionIntDefaultValue: DefaultValue[Option[Int]] = org.scalatra.DefaultValueMethods.default(None)

  import util.RicherString._


  implicit val stringtoOptionInt: TypeConverter[String, Option[Int]] = safe(_.blankOption.map (_.toInt))

  implicit val jsontoOptionInt: TypeConverter[JValue, Option[Int]] = safe((jvalue: JValue) => jvalue match {
    case JInt(value) => Some(value.toInt)
    case _ => None
  })


  implicit val stringtoOptionString: TypeConverter[String, Option[String]] = safe(_.blankOption)

  implicit val jsontoOptionString: TypeConverter[JValue, Option[String]] = safe((jvalue: JValue) => jvalue match {
    case JString(value) => Some(value)
    case _ => None
  })


  val arvio: Field[String] = asType[String]("arvio.arvosana").notBlank
  val asteikko: Field[String] = asType[String]("arvio.asteikko").required.allowableValues(Arvio.asteikot:_*)
  val pisteet: Field[Option[Int]] = asType[Option[Int]]("arvio.pisteet").optional
  val suoritus: Field[String] = asType[String]("suoritus").required.validForFormat("([a-f\\d]{8}(-[a-f\\d]{4}){3}-[a-f\\d]{12}?)".r,"%s is not a valid UUID")
  val aine: Field[String] = asType[String]("aine").notBlank
  val myonnetty: Field[LocalDate] = asType[LocalDate]("myonnetty").optional

  val lisatieto: Field[Option[String]] = asType[Option[String]]("lisatieto").optional
  val valinnainen: Field[Boolean] = asType[Boolean]("valinnainen").optional



  override def toResource(user: String): Arvosana = Arvosana(suoritus.value.map(UUID.fromString).get, Arvio(arvio.value.get, asteikko.value.get, pisteet.value.get), aine.value.get, lisatieto.value.get, valinnainen.value.getOrElse(false), myonnetty.value, source = user)
}




