package fi.vm.sade.hakurekisteri.web.rest.support

import java.io.InputStream

import fi.vm.sade.hakurekisteri.integration.parametrit.KierrosParams
import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriJsonSupport, Workbook}
import fi.vm.sade.hakurekisteri.suoritus.yksilollistaminen
import fi.vm.sade.hakurekisteri.suoritus.yksilollistaminen._
import fi.vm.sade.hakurekisteri.tools.SafeXML
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.json4s.JsonAST.{JInt, JString, JValue}
import org.json4s._
import org.scalatra.NotFound
import org.scalatra.commands._
import org.scalatra.forms.{FormSupport, MappingValueType}
import org.scalatra.i18n.I18nSupport
import org.scalatra.json.JsonValueReader
import org.scalatra.servlet.{FileItem, ServletBase}
import org.scalatra.util.ValueReader
import org.scalatra.util.conversion.{TypeConverter, TypeConverterSupport}
import org.scalatra.validation.{FieldName, UnknownError, ValidationError}
import org.xml.sax.SAXParseException
import siirto.ArvosanatXmlConverter._
import siirto.XMLValidator

import scala.concurrent.Future
import scala.language.implicitConversions
import scala.util.Try
import scala.util.control.Exception._
import scala.xml.Elem
import scalaz.Scalaz._
import scalaz._
import scalaz.Validation.FlatMap._

object NoXmlConverterSpecifiedException extends Exception(s"no xml converter specified")

trait HakurekisteriCommand[R] extends HakurekisteriJsonSupport with FileItemOperations with FormSupport with I18nSupport with ServletBase {


  protected def doNotFound_=(x$1: org.scalatra.Action): Unit = NotFound()
  def requestPath(implicit request: javax.servlet.http.HttpServletRequest): String = ???
  protected def routeBasePath(implicit request: javax.servlet.http.HttpServletRequest): String = ???

  //type CommandTypeConverterFactory[T] = FileTypeConverterFactory[T]

  /*override def typeConverterBuilder[I](tc: CommandTypeConverterFactory[_]) = ({
    case r: JsonValueReader => tc.resolveJson.asInstanceOf[TypeConverter[I, _]]
    case f: FileItemMapValueReader => tc.resolveFiles.asInstanceOf[TypeConverter[I, _]]

  }: PartialFunction[ValueReader[_, _], TypeConverter[I, _]]) orElse super.typeConverterBuilder(tc)*/


  import org.json4s.jackson.JsonMethods.parse
  import org.scalatra.util.RicherString._

  implicit val stringtoJValue: TypeConverter[String, JValue] = safe((s: String) => parse(s))

  implicit val jsontoJValue: TypeConverter[JValue, JValue] = safe((jvalue: JValue) => jvalue)

  implicit val stringtoXml: TypeConverter[String, Elem] = safe((s: String) => SafeXML.loadString(s))

  implicit val jsontoXml: TypeConverter[JValue, Elem] = safe((jvalue: JValue) => {
    val JString(data) = jvalue
    SafeXML.loadString(data)
  })


  implicit val excelConverter = new XmlConverter {
    override def convert(workbook: Workbook, filename: String): Elem = throw NoXmlConverterSpecifiedException
  }

  implicit val fileToXml: TypeConverter[FileItem, Elem] = safe {(f: FileItem) =>
    def xml(f: FileItem): Boolean = f.getContentType.exists(t => t == "text/xml" || t == "application/xml") || f.extension.contains("xml")

    if (xml(f)) {
      SafeXML.load(f.getInputStream)
    }
    else {
      excelConverter.convert(f)
    }}

  implicit val stringtoOptionInt: TypeConverter[String, Option[Int]] = safe(_.blankOption.map (_.toInt))

  implicit val jsontoOptionInt: TypeConverter[JValue, Option[Int]] = safe((jvalue: JValue) => jvalue match {
    case JInt(value) => Some(value.toInt)
    case _ => None
  })

  //implicit val fileToOptionInt: TypeConverter[FileItem, Option[Int]] = safe(_.blankOption)

  implicit val stringtoOptionString: TypeConverter[String, Option[String]] = safe(_.blankOption)

  implicit val jsontoOptionString: TypeConverter[JValue, Option[String]] = safe((jvalue: JValue) => jvalue match {
    case JString(value) => Some(value)
    case _ => None
  })

  implicit val jsonToKierrosParamsMap: TypeConverter[JValue, Map[String, KierrosParams]] = safe((jvalue: JValue) => jvalue match {
    case JObject(m) => m.collect {
      case (key, value) if Try(value.asInstanceOf[KierrosParams]).isSuccess =>
        key -> value.asInstanceOf[KierrosParams]
    }.toMap
    case _ => Map[String, KierrosParams]()
  })

  //implicit val fileToOptionString: TypeConverter[FileItem, Option[String]] = cantConvert

  implicit val stringToYksilollistaminen: TypeConverter[String, Yksilollistetty] = safeOption(_.blankOption.map (yksilollistaminen.withName))
  implicit val jsonToYksilollistaminen: TypeConverter[JValue, Yksilollistetty] = safeOption(_.extractOpt[Yksilollistetty])

  //implicit val fileToYksilollistaminen: TypeConverter[FileItem, Yksilollistetty] = cantConvert

  //implicit def xmlFieldToValidatable(field: FieldDescriptor[Elem]):ValidatableXml = new ValidatableXml(field)

  def toResource(user: String): R

  //def toForm(): MappingValueType[Any] //fixme type, override this elsewhere

  //def errorFail(ex: Throwable) = ValidationError(ex.getMessage, UnknownError).failureNel

  //def extraValidation(res: R): ValidationNel[ValidationError, R] = res.successNel

  def toValidatedResource(user: String) = {
    Future.successful(toResource(user))
  }

  /*def toValidatedResource(user: String): Future[ModelValidation[R]] =  {
    Future.successful(
      allCatch.withApply(errorFail) {
        extraValidation(toResource(user))
      }
    )
  }*/
}

/*class ValidatableXml(b: FieldDescriptor[Elem]) {
  def validateSchema(implicit validator: XMLValidator[ValidationNel[(String, SAXParseException), Elem],NonEmptyList[(String, SAXParseException)], Elem]): FieldDescriptor[Elem] =
    b.validateWith(abidesSchema(validator))

  def abidesSchema(validator: XMLValidator[ValidationNel[(String, SAXParseException), Elem],NonEmptyList[(String, SAXParseException)], Elem]): BindingValidator[Elem] = (s: String) => {
    _.flatMap(validateSchema(s, validator))
  }

  def validateSchema(field: String, validator: XMLValidator[ValidationNel[(String, SAXParseException), Elem],NonEmptyList[(String, SAXParseException)], Elem]): (Elem) => FieldValidation[Elem] = (xml)  => {
    validator.validate(xml).leftMap(saxErrors => {
      val errors = saxErrors.map(_._2)
      new ValidationError("Xml validation failed", Some(FieldName(field)), None, errors.toList)
    })
  }
}*/

trait XmlConverter {
  def convert(f: FileItem): Elem = f match {
    case excelFile if isExcel(excelFile) =>
      convert(excelFile.getInputStream, excelFile.getName)
    case file =>
      throw new IllegalArgumentException(s"file ${file.getName} cannot be converted to xml")
  }

  def convert(inputStream: InputStream, filename: String): Elem = {
    convert(Workbook(WorkbookFactory.create(inputStream)), filename)
  }

  def convert(workbook: Workbook, filename: String): Elem
}


/*
trait FileTypeConverterFactory[T] extends JsonTypeConverterFactory[T] {

  def resolveFiles: TypeConverter[FileItem, T]

}
*/

/*trait HakurekisteriTypeConverterFactories extends FileBindingImplicits {
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
}*/

trait FileBindingImplicits extends TypeConverterSupport {

  def cantConvert[S,T] = new TypeConverter[S, T] {
    override def apply(s: S): Option[T] = None
  }

  implicit lazy val fileToString: TypeConverter[FileItem, String] = safe(f => scala.io.Source.fromInputStream(f.getInputStream).getLines().mkString("\n"))


  implicit lazy val fileToBoolean: TypeConverter[FileItem, Boolean] = cantConvert



}

class FileItemMapValueReader(val data: Map[String, FileItem]) extends ValueReader[Map[String, FileItem], FileItem] {
  def read(key: String): Either[String, Option[FileItem]] =
    allCatch.withApply(t => Left(t.getMessage)) { Right(data get key) }
}


trait FileItemOperations {

  trait RichFileItem {
    val f: FileItem

    val SafeExtension = ".*\\.([a-zA-Z0-9]{1,10}$)".r

    def extension = f.getName match {
      case SafeExtension(extension) => Some(extension)
      case _ => None

    }
  }

  implicit def fileItem2ToRichFileItem(item: FileItem): RichFileItem = new RichFileItem {
    override val f: FileItem = item
  }

}

object FileItemOperations extends FileItemOperations
