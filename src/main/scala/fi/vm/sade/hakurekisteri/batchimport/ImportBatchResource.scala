package fi.vm.sade.hakurekisteri.batchimport

import java.io.{PrintWriter, File, ByteArrayInputStream, InputStream}
import java.text.SimpleDateFormat
import java.util
import java.util.{Date, UUID}
import javax.servlet.http.{Part, HttpServletRequest}

import _root_.akka.event.{Logging, LoggingAdapter}
import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.{AskTimeoutException, ask}
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.rest.support._
import org.json4s.Extraction
import org.scalatra.util.ValueReader
import org.scalatra._
import org.scalatra.commands._
import org.scalatra.servlet.{FileItem, SizeConstraintExceededException, MultipartConfig, FileUploadSupport}
import org.scalatra.swagger.{DataType, SwaggerSupport, Swagger}
import org.scalatra.swagger.SwaggerSupportSyntax.OperationBuilder
import org.xml.sax.SAXParseException
import siirto.{XMLValidator, ValidXml, SchemaDefinition}

import scala.compat.Platform
import scala.concurrent.duration._
import scala.io
import scala.io.BufferedSource
import scala.util.Try
import scala.xml.Elem
import scalaz._


class ImportBatchResource(eraRekisteri: ActorRef,
                                   queryMapper: (Map[String, String]) => Query[ImportBatch])
                                  (externalIdField: String,
                                   batchType: String,
                                   dataField: String,
                                   schema: SchemaDefinition,
                                   imports: SchemaDefinition*)
                                  (implicit sw: Swagger, system: ActorSystem, mf: Manifest[ImportBatch], cf: Manifest[ImportBatchCommand])
    extends HakurekisteriResource[ImportBatch, ImportBatchCommand](eraRekisteri, queryMapper) with ImportBatchSwaggerApi with HakurekisteriCrudCommands[ImportBatch, ImportBatchCommand] with SpringSecuritySupport with FileUploadSupport with IncidentReporting {

  override val logger: LoggingAdapter = Logging.getLogger(system, this)

  val maxFileSize = 50 * 1024 * 1024L
  val storageDir = Config.tiedonsiirtoStorageDir
  
  configureMultipartHandling(MultipartConfig(
    maxFileSize = Some(maxFileSize)
  ))

  logger.info(s"storageDir: $storageDir")

  val validator = new ValidXml(schema, imports:_*)

  val schemaCache = (schema +: imports).map((sd) => sd.schemaLocation -> sd.schema).toMap


  registerCommand[ImportBatchCommand](ImportBatchCommand(externalIdField,
                                                         batchType,
                                                         dataField,
                                                         new ValidXml(schema, imports:_*)))

  before() {
    if (multipart) contentType = formats("html")
  }

  private def ensureStorageDir() = {
    try {
      if (new File(storageDir).mkdirs()) {
        logger.info(s"creating a temp dir in $storageDir")
      }
    } catch {
      case e: Exception =>
        logger.error(e, "error while ensuring storageDir exists")
        throw e
    }
  }

  ensureStorageDir()

  private def toJson(a: Product): String = compact(Extraction.decompose(a))

  private def renderAsJson: RenderPipeline = {
    case a: IncidentReport if format == "html" => toJson(a)
    case a: ImportBatch if format == "html" => toJson(a)
  }

  override protected def renderPipeline: RenderPipeline = renderAsJson orElse super.renderPipeline

  def getUser: User = {
    currentUser match {
      case Some(u) => u
      case None => throw UserNotAuthorized("anonymous access not allowed")
    }
  }

  get("/schema") {
    MovedPermanently(request.getRequestURL.append("/").append(schema.schemaLocation).toString)
  }

  get("/mybatches") {
    val user = getUser
    new AsyncResult() {
      override implicit def timeout: Duration = 60.seconds
      override val is = eraRekisteri.?(BatchesBySource(user.username))(60.seconds)
    }
  }

  get("/withoutdata") {
    val user = getUser
    if (!user.orgsFor("READ", "ImportBatch").contains(Config.ophOrganisaatioOid)) throw UserNotAuthorized("access not allowed")
    else new AsyncResult() {
      override implicit def timeout: Duration = 60.seconds
      override val is = eraRekisteri.?(AllBatchStatuses)(60.seconds)
    }
  }

  get("/schema/:schema") {
    schemaCache.get(params("schema")).fold(NotFound("not found")){
      contentType = "application/xml"
      Ok(_)
    }
  }

  post("/reprocess/:id") {
    val user = getUser
    if (!user.orgsFor("WRITE", "ImportBatch").contains(Config.ophOrganisaatioOid)) throw UserNotAuthorized("access not allowed")
    else new AsyncResult() {
      override implicit def timeout: Duration = 60.seconds
      val id = Try(UUID.fromString(params("id"))).get
      logger.debug(s"starting to reprocess $id")
      override val is = eraRekisteri.?(Reprocess(id))(60.seconds)
    }
  }

  incident {
    case t: WrongBatchStateException => (id) => BadRequest(IncidentReport(id, "illegal state for reprocessing"))
    case BatchNotFoundException => (id) => NotFound(IncidentReport(id, "batch not found for reprocessing"))
    case t: NotFoundException => (id) => NotFound(IncidentReport(id, "resource not found"))
    case t: MalformedResourceException => (id) => BadRequest(IncidentReport(incidentId = id, message = t.getMessage, validationErrors = t.errors.map(_.args.map(_.toString)).list.reduce(_ ++ _).toSeq))
    case t: UserNotAuthorized => (id) => Forbidden(IncidentReport(id, "not authorized"))
    case t: SizeConstraintExceededException => (id) => RequestEntityTooLarge(IncidentReport(id, s"Tiedosto on liian suuri (suurin sallittu koko $maxFileSize tavua)."))
    case t: IllegalArgumentException => (id) => BadRequest(IncidentReport(id, t.getMessage))
    case t: AskTimeoutException => (id) => InternalServerError(IncidentReport(id, "Taustajärjestelmä ei vastaa. Yritä myöhemmin uudelleen."))
    case t: Throwable => (id) => InternalServerError(IncidentReport(id, "Tuntematon virhe. Yritä uudelleen hetken kuluttua."))
  }

  def multipart(implicit request: HttpServletRequest) = {
    val isPostOrPut = Set("POST", "PUT", "PATCH").contains(request.getMethod)
    isPostOrPut && (request.contentType match {
      case Some(contentType) => contentType.startsWith("multipart/")
      case _ => false
    })
  }

  val SafeExtension = ".*\\.[a-zA-Z0-9]{1,10}$".r
  private def getFileExtension(f: FileItem): String = f.name match {
    case SafeExtension() => f.name.substring(f.name.lastIndexOf('.') + 1)
    case _ => "unknown"
  }

  val tsFormat = "yyyyMMddHHmmssSSS"
  private def saveFiles(valid: Boolean)(implicit request: HttpServletRequest): Map[String, File] = {
    val ts = new SimpleDateFormat(tsFormat).format(new Date())
    val filename =
      if (valid) s"$storageDir/${ts}_${UUID.randomUUID()}"
      else s"$storageDir/${ts}_${UUID.randomUUID()}_invalid"
    if (multipart(request)) {
      fileMultiParams.get(dataField).getOrElse(Seq.empty[FileItem]).map((f: FileItem) => {
        val newFile = new File(s"$filename.${getFileExtension(f)}")
        f.write(newFile)
        f.name -> newFile
      }).toMap
    } else {
      val newFile = new File(s"$filename.xml")
      new PrintWriter(newFile).write(request.body)
      Map("as request body" -> newFile)
    }
  }

  override protected def bindCommand[T <: CommandType](newCommand: T)(implicit request: HttpServletRequest, mf: Manifest[T]): T = {
    val command = request match {
      case r if multipart(r) => newCommand.bindTo[Map[String, FileItem], FileItem](fileParams, multiParams(request), request.headers)(files => new FileItemMapValueReader(files), default(EmptyFile), default(Map()), manifest[FileItem], implicitly[MultiParams => ValueReader[MultiParams, Seq[String]]])
      case _ => newCommand.bindTo(params(request) + (dataField -> request.body), multiParams(request), request.headers)
    }

    saveFiles(command.isValid).foreach(entry => {
      logger.info(s"received ${if (command.isValid) "a valid" else "an invalid"} batch (${entry._1}) from ${getUser.username}, saving to storage as ${entry._2.getName}")
    })

    command
  }
}

case class ImportBatchCommand(externalIdField: String, batchType: String, dataField: String, validator: XMLValidator[ValidationNel[(String, SAXParseException), Elem],NonEmptyList[(String, SAXParseException)], Elem]) extends HakurekisteriCommand[ImportBatch] {
  implicit val valid = validator

  val data: Field[Elem] = asType[Elem](dataField).required.validateSchema

  override def toResource(user: String): ImportBatch = ImportBatch(data.value.get, data.value.flatMap(elem => (elem \ externalIdField).collectFirst{case e:Elem => e.text}), batchType, user, BatchState.READY, ImportStatus())
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
    .notes("Vastaanottaa XML-tiedoston joko lomakkeen kenttänä multipart-koodattuna (kentän nimi 'data') tai XML-muodossa requestin bodyssä. " +
      "Tiedoston voi lähettää esimerkiksi curl-ohjelmaa käyttäen näin: " +
      "<pre>" +
      "<code class='bash'>$ curl -F \"data=@perustiedot.xml\" -H \"CasSecurityTicket: ST-tiketti\" https://testi.virkailija.opintopolku.fi/suoritusrekisteri/rest/v1/siirto/perustiedot</code>" +
      "</pre> " +
      "<br /><a href='/suoritusrekisteri/rest/v1/siirto/perustiedot/schema'>Perustietojen XML-skeema</a>")
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