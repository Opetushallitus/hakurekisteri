package fi.vm.sade.hakurekisteri.rest.support

import org.scalatra.commands._
import fi.vm.sade.hakurekisteri.suoritus.yksilollistaminen
import org.scalatra.json.JsonValueReader
import org.scalatra.servlet.FileItem
import org.scalatra.util.ValueReader
import org.scalatra.validation.{FieldName, UnknownError, ValidationError}
import org.xml.sax.SAXParseException
import siirto.{XMLValidator, ValidXml}
import scala.util.control.Exception._
import scala.xml.{Elem, XML}
import scalaz._, Scalaz._
import org.scalatra.{DefaultValues, util, DefaultValue}
import org.scalatra.util.conversion.TypeConverter
import org.json4s._
import fi.vm.sade.hakurekisteri.suoritus.yksilollistaminen._
import org.json4s.JsonAST.JString
import org.json4s.JsonAST.JInt
import scala.concurrent.Future
import scala.language.implicitConversions


trait HakurekisteriCommand[R] extends Command with HakurekisteriTypeConverterFactories with HakurekisteriJsonSupport{

  type CommandTypeConverterFactory[T] = FileTypeConverterFactory[T]

  override def typeConverterBuilder[I](tc: CommandTypeConverterFactory[_]) = ({
    case r: JsonValueReader => tc.resolveJson.asInstanceOf[TypeConverter[I, _]]
    case f: FileItemMapValueReader => tc.resolveFiles.asInstanceOf[TypeConverter[I, _]]

  }: PartialFunction[ValueReader[_, _], TypeConverter[I, _]]) orElse super.typeConverterBuilder(tc)


  implicit def OptionIntDefaultValue: DefaultValue[Option[Int]] = org.scalatra.DefaultValueMethods.default(None)

  import util.RicherString._

  import org.json4s.jackson.JsonMethods.parse

  implicit val stringtoJValue: TypeConverter[String, JValue] = safe((s: String) => parse(s))

  implicit val jsontoJValue: TypeConverter[JValue, JValue] = safe((jvalue: JValue) => jvalue)

  implicit val defaultElem: DefaultValue[Elem] = DefaultValues.ElemDefaultValue

  implicit val stringtoXml: TypeConverter[String, Elem] = safe((s: String) => XML.loadString(s))

  implicit val jsontoXml: TypeConverter[JValue, Elem] = safe((jvalue: JValue) => {
    val JString(data) = jvalue
    XML.loadString(data)
  })

  implicit val fileToXml: TypeConverter[FileItem, Elem] = safe((f: FileItem) => XML.load(f.getInputStream))

  implicit val stringtoOptionInt: TypeConverter[String, Option[Int]] = safe(_.blankOption.map (_.toInt))

  implicit val jsontoOptionInt: TypeConverter[JValue, Option[Int]] = safe((jvalue: JValue) => jvalue match {
    case JInt(value) => Some(value.toInt)
    case _ => None
  })


  implicit val fileToOptionInt: TypeConverter[FileItem, Option[Int]] = cantConvert


  implicit val stringtoOptionString: TypeConverter[String, Option[String]] = safe(_.blankOption)

  implicit val jsontoOptionString: TypeConverter[JValue, Option[String]] = safe((jvalue: JValue) => jvalue match {
    case JString(value) => Some(value)
    case _ => None
  })

  implicit val fileToOptionString: TypeConverter[FileItem, Option[String]] = safe(f => scala.io.Source.fromInputStream(f.getInputStream).getLines().mkString("\n").blankOption)


  implicit def YksilollistaminenDefaultValue: DefaultValue[Yksilollistetty] = org.scalatra.DefaultValueMethods.default(Ei)

  implicit val stringToYksilollistaminen: TypeConverter[String, Yksilollistetty] = safeOption(_.blankOption.map (yksilollistaminen.withName))
  implicit val jsonToYksilollistaminen: TypeConverter[JValue, Yksilollistetty] = safeOption(_.extractOpt[Yksilollistetty])

  implicit val fileToYksilollistaminen: TypeConverter[FileItem, Yksilollistetty] = cantConvert

  implicit def xmlFieldToValidatable(field: FieldDescriptor[Elem]):ValidatableXml = new ValidatableXml(field)

  def toResource(user: String): R

  def errorFail(ex: Throwable) = ValidationError(ex.getMessage, UnknownError).failNel

  def extraValidation(res: R): ValidationNel[ValidationError, R] = res.successNel

  def toValidatedResource(user: String): Future[ModelValidation[R]] =  {
    Future.successful(
      allCatch.withApply(errorFail) {
        extraValidation(toResource(user))
      }
    )
  }
}

class ValidatableXml(b: FieldDescriptor[Elem]) {
  def validateSchema(implicit validator: XMLValidator[ValidationNel[(String, SAXParseException), Elem],NonEmptyList[(String, SAXParseException)], Elem]): FieldDescriptor[Elem] =
    b.validateWith(abidesSchema(validator))

  def abidesSchema(validator: XMLValidator[ValidationNel[(String, SAXParseException), Elem],NonEmptyList[(String, SAXParseException)], Elem]): BindingValidator[Elem] = (s: String) => {
    _ flatMap (validateSchema(s, validator))
  }

  def validateSchema(field: String, validator: XMLValidator[ValidationNel[(String, SAXParseException), Elem],NonEmptyList[(String, SAXParseException)], Elem]): (Elem) => FieldValidation[Elem] = (xml)  => {
    validator.validate(xml).leftMap(saxErrors => {
      val errors = saxErrors.map(_._2)
      new ValidationError("Xml validation failed", Some(FieldName(field)), None,  errors.toList)
    })
  }
}


trait FileTypeConverterFactory[T] extends JsonTypeConverterFactory[T] {

  def resolveFiles: TypeConverter[FileItem, T]



}

trait HakurekisteriTypeConverterFactories extends FileBindingImplicits  {
  implicit def fileTypeConverterFactory[T](implicit
                                           seqConverter: TypeConverter[Seq[String], T],
                                           stringConverter: TypeConverter[String, T],
                                           jsonConverter: TypeConverter[JValue, T],
                                           fileConverter: TypeConverter[FileItem, T],
                                           formats: Formats): TypeConverterFactory[T] =
    new FileTypeConverterFactory[T] {
      implicit protected val jsonFormats: Formats = formats
      def resolveJson: TypeConverter[JValue,  T] = jsonConverter
      def resolveMultiParams: TypeConverter[Seq[String], T] = seqConverter
      def resolveStringParams: TypeConverter[String, T] = stringConverter
      def resolveFiles: TypeConverter[FileItem, T] = fileConverter
    }
}

trait FileBindingImplicits extends JsonBindingImplicits {

  def cantConvert[S,T] = new TypeConverter[S, T] {
    override def apply(s: S): Option[T] = None
  }

  implicit val fileToString: TypeConverter[FileItem, String] = safe(f => scala.io.Source.fromInputStream(f.getInputStream).getLines().mkString("\n"))


  implicit val fileToBoolean: TypeConverter[FileItem, Boolean] = cantConvert



}

class FileItemMapValueReader(val data: Map[String, FileItem]) extends ValueReader[Map[String, FileItem], FileItem] {
  def read(key: String): Either[String, Option[FileItem]] =
    allCatch.withApply(t => Left(t.getMessage)) { Right(data get key) }
}
