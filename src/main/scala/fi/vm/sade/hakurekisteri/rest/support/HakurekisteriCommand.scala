package fi.vm.sade.hakurekisteri.rest.support

import org.scalatra.commands.{ModelValidation, JsonCommand}
import fi.vm.sade.hakurekisteri.suoritus.{yksilollistaminen, Komoto, Suoritus}
import org.scalatra.validation.{UnknownError, ValidationError}
import scala.util.control.Exception._
import org.scalatra.validation._
import scalaz._, Scalaz._
import org.scalatra.{util, DefaultValue}
import org.scalatra.util.conversion.TypeConverter
import org.json4s._
import scala.Some
import org.json4s.JsonAST.{JString, JInt}
import fi.vm.sade.hakurekisteri.suoritus.yksilollistaminen._
import org.json4s.JsonAST.JString
import org.json4s.JsonAST.JInt
import scala.Some

trait HakurekisteriCommand[R] extends  JsonCommand  with HakurekisteriJsonSupport{


  implicit def OptionIntDefaultValue: DefaultValue[Option[Int]] = org.scalatra.DefaultValueMethods.default(None)

  import util.RicherString._

  import org.json4s.jackson.JsonMethods.parse

  implicit val stringtoJValue: TypeConverter[String, JValue] = safe((s: String) => parse(s))

  implicit val jsontoJValue: TypeConverter[JValue, JValue] = safe((jvalue: JValue) => jvalue)




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

  implicit def YksilollistaminenDefaultValue: DefaultValue[Yksilollistetty] = org.scalatra.DefaultValueMethods.default(Ei)

  implicit val stringToYksilollistaminen: TypeConverter[String, Yksilollistetty] = safeOption(_.blankOption.map (yksilollistaminen.withName))
  implicit val jsonToYksilollistaminen: TypeConverter[JValue, Yksilollistetty] = safeOption(_.extractOpt[Yksilollistetty])


  def toResource(user: String): R

  def errorFail(ex: Throwable) = ValidationError(ex.getMessage, UnknownError).failNel

  def toValidatedResource(user: String): ModelValidation[R] =  {

    allCatch.withApply(errorFail) {
      toResource(user).successNel
    }
  }
}
