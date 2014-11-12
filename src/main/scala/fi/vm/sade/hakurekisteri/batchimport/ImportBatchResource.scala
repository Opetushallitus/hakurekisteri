package fi.vm.sade.hakurekisteri.batchimport

import java.io.{ByteArrayInputStream, InputStream}
import java.util
import javax.servlet.http.{Part, HttpServletRequest}

import _root_.akka.actor.{ActorRef, ActorSystem}
import _root_.akka.pattern.AskTimeoutException
import fi.vm.sade.hakurekisteri.rest.support._
import org.json4s.Extraction
import org.scalatra.util.ValueReader
import org.scalatra._
import org.scalatra.commands._
import org.scalatra.servlet.{FileItem, SizeConstraintExceededException, MultipartConfig, FileUploadSupport}
import org.scalatra.swagger.{DataType, SwaggerSupport, Swagger}
import org.scalatra.swagger.SwaggerSupportSyntax.OperationBuilder
import org.scalatra.validation.ValidationError

import scala.util.control.Exception._
import scala.xml.Elem
import scalaz._


class ImportBatchResource(eraRekisteri: ActorRef,
                                   queryMapper: (Map[String, String]) => Query[ImportBatch])
                                  (externalIdField: String,
                                   batchType: String,
                                   dataField: String,
                                   validations: (String, Elem => ValidationNel[ValidationError, Elem])*)
                                  (implicit sw: Swagger, system: ActorSystem, mf: Manifest[ImportBatch], cf: Manifest[ImportBatchCommand])
    extends HakurekisteriResource[ImportBatch, ImportBatchCommand](eraRekisteri, queryMapper) with ImportBatchSwaggerApi with HakurekisteriCrudCommands[ImportBatch, ImportBatchCommand] with SpringSecuritySupport with FileUploadSupport with IncidentReporting {

  val maxFileSize = 50 * 1024 * 1024L
  configureMultipartHandling(MultipartConfig(maxFileSize = Some(maxFileSize)))

  registerCommand[ImportBatchCommand](ImportBatchCommand(externalIdField,
                                                         batchType,
                                                         dataField,
                                                         validations:_*))

  before() {
    if (multipart) contentType = formats("html")
  }

  def toJson(p: Product): String = compact(Extraction.decompose(p))

  incident {
    case t: NotFoundException => (id) => NotFound(IncidentReport(id, "resource not found"))
    case t: MalformedResourceException => (id) => BadRequest(IncidentReport(id, t.getMessage))
    case t: UserNotAuthorized => (id) => Forbidden(IncidentReport(id, "not authorized"))
    case t: SizeConstraintExceededException => (id) => RequestEntityTooLarge(toJson(IncidentReport(id, s"Tiedosto on liian suuri (suurin sallittu koko $maxFileSize tavua).")))
    case t: IllegalArgumentException => (id) => BadRequest(toJson(IncidentReport(id, t.getMessage)))
    case t: AskTimeoutException => (id) => InternalServerError(toJson(IncidentReport(id, "Taustajärjestelmä ei vastaa. Yritä myöhemmin uudelleen.")))
    case t: Throwable => (id) => InternalServerError(toJson(IncidentReport(id, "Tuntematon virhe. Yritä uudelleen hetken kuluttua.")))
  }




  def multipart(implicit request: HttpServletRequest) = {
    val isPostOrPut = Set("POST", "PUT", "PATCH").contains(request.getMethod)
    isPostOrPut && (request.contentType match {
      case Some(contentType) => contentType.startsWith("multipart/")
      case _ => false
    })
  }



  override protected def bindCommand[T <: CommandType](newCommand: T)(implicit request: HttpServletRequest, mf: Manifest[T]): T = {
    if (multipart)
      newCommand.bindTo[Map[String, FileItem], FileItem](fileParams, multiParams(request), request.headers)(files => new FileItemMapValueReader(files), default(EmptyFile), default(Map()), manifest[FileItem], implicitly[MultiParams => ValueReader[MultiParams, Seq[String]]])
    else
      newCommand.bindTo(params(request) + (dataField -> request.body), multiParams(request), request.headers)
  }
}

case class ImportBatchCommand(externalIdField: String, batchType: String, dataField: String, validations: (String, Elem => ValidationNel[ValidationError, Elem])*) extends HakurekisteriCommand[ImportBatch] {


  private val validatedData = asType[Elem](dataField).required
  val data: Field[Elem] = validatedData

  override def toResource(user: String): ImportBatch = ImportBatch(data.value.get, data.value.flatMap(elem => (elem \ externalIdField).collectFirst{case e:Elem => e.text}), batchType, user)

  import scalaz._, Scalaz._

  override def extraValidation(batch: ImportBatch): ValidationNel[ValidationError, ImportBatch] = {
    val xml = batch.data
    val validation = validations.map(_._2).foldLeft(xml.successNel[ValidationError])((validated: ValidationNel[ValidationError, Elem], validation:  (Elem) => ValidationNel[ValidationError, Elem]) => validated.flatMap(validation))
    validation.map((validated) => batch.copy(data = validated))
  }

}

trait ImportBatchSwaggerApi extends SwaggerSupport with OldSwaggerSyntax {
  protected def applicationDescription: String = "Perustietojen tiedonsiirto"

  registerModel(Model("ImportBatch", "ImportBatch", Seq[ModelField](
    ModelField("externalId", "lähettäjän määrittämä tunniste, luetaan tiedoston elementistä 'eranTunniste'", DataType.String, required = false),
    ModelField("batchType", "lähetyksen tyyppi", DataType.String, Some("perustiedot")),
    ModelField("data", "lähetetty data", DataType.String)
  ).map(t => (t.name, t)).toMap))

  val update: OperationBuilder = apiOperation[ImportBatch]("N/A 1")
  val delete: OperationBuilder = apiOperation[ImportBatch]("N/A 2")
  val read: OperationBuilder = apiOperation[ImportBatch]("N/A 3")
  val create: OperationBuilder = apiOperation[ImportBatch]("lahetaTiedosto")
    .summary("vastaanottaa tiedoston")
    .notes("Vastaanottaa XML-tiedoston joko lomakkeen kenttänä multipart-koodattuna (kentän nimi 'data') tai XML-muodossa requestin bodyssä. <a href='/suoritusrekisteri/schemas/perustiedot.xsd'>Perustietojen XML-skeema</a>")
    .consumes("application/xml", "multipart/form-data")
    .parameter(bodyParam[String].description("XML-tiedosto").required)
  val query: OperationBuilder = apiOperation[ImportBatch]("N/A 4")
}

object EmptyFile extends FileItem(EmptyPart)

object EmptyPart extends Part {
  import scala.collection.JavaConversions._
  private val data: Array[Byte] = new Array[Byte](0)
  override def getInputStream: InputStream = new ByteArrayInputStream(data)
  override def getSubmittedFileName: String = ""
  override def getHeaderNames: util.Collection[String] = List()
  override def getName: String = ""
  override def getSize: Long = data.length
  override def getHeaders(name: String): util.Collection[String] = List()
  override def delete(): Unit = {}
  override def write(fileName: String): Unit = {}
  override def getContentType: String = "text/plain"
  override def getHeader(name: String): String = null
}