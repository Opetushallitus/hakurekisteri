package fi.vm.sade.hakurekisteri.arvosana

import org.scalatra.commands._
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriCommand
import java.util.UUID
import org.scalatra.{util, DefaultValue}
import org.joda.time.{DateTime, LocalDate}
import org.scalatra.util.conversion.TypeConverter
import org.joda.time.format.DateTimeFormat
import org.json4s._


class CreateArvosanaCommand extends HakurekisteriCommand[Arvosana] {

  val defaultUUID: UUID = UUID.fromString("12345678-1234-1234-1234-123456789012")


  implicit def UUIDDefaultValue: DefaultValue[UUID] = org.scalatra.DefaultValueMethods.default(defaultUUID)
  implicit def ArvioDefault: DefaultValue[Arvio] = org.scalatra.DefaultValueMethods.default(Arvio.NA)

  import util.RicherString._
  implicit val stringToUUID: TypeConverter[String, UUID] = safeOption((in:String)=>in.blankOption.map(UUID.fromString(_)))
  implicit val jsonToUUID: TypeConverter[JValue, UUID] = safeOption(_.extractOpt[UUID])




  val arvio: Field[String] = asType[String]("arvio.arvosana").notBlank
  val asteikko: Field[String] = asType[String]("arvio.asteikko").required.allowableValues(Arvio.asteikot:_*)
  val suoritus: Field[UUID] = asType[UUID]("suoritus").required
  val aine: Field[String] = asType[String]("aine").notBlank
  val lisatieto: Field[String] = asType[String]("lisatieto").optional
  val valinnainen: Field[Boolean] = asType[Boolean]("valinnainen").optional

  override def toResource: Arvosana = Arvosana(suoritus.value.get, Arvio(arvio.value.get, asteikko.value.get), aine.value.get, lisatieto.value, valinnainen.value.get)
}


