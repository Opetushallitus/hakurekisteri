package fi.vm.sade.hakurekisteri.web.batchimport

import java.io.{ByteArrayInputStream, File, InputStream, PrintWriter}
import java.text.SimpleDateFormat
import java.util
import java.util.{Date, UUID}

import javax.servlet.http.{HttpServletRequest, Part}
import _root_.akka.actor.{ActorRef, ActorSystem}
import _root_.akka.event.{Logging, LoggingAdapter}
import _root_.akka.pattern.{AskTimeoutException, ask}
import fi.vm.sade.hakurekisteri.integration.organisaatio.{ChildOids, GetChildOids, OrganisaatioActorRef}
import fi.vm.sade.hakurekisteri.organization.{AuthorizedQuery, AuthorizedRead, AuthorizedReadWithOrgsChecked}
import fi.vm.sade.hakurekisteri.{Config, Oids}
import fi.vm.sade.hakurekisteri.batchimport.{ImportBatch, ImportStatus, Reprocess, WrongBatchStateException, _}
import fi.vm.sade.hakurekisteri.integration.parametrit.{IsSendingEnabled, ParametritActorRef}
import fi.vm.sade.hakurekisteri.integration.valintatulos.{Ilmoittautumistila, Valintatila, Vastaanottotila}
import fi.vm.sade.hakurekisteri.rest.support._
import fi.vm.sade.hakurekisteri.batchimport.QueryImportBatchReferences
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.suoritus.yksilollistaminen
import fi.vm.sade.hakurekisteri.web.rest.support._
import org.json4s.Extraction
import org.scalatra._
import org.scalatra.commands._
import org.scalatra.servlet.{FileItem, FileUploadSupport, MultipartConfig, SizeConstraintExceededException}
import org.scalatra.swagger.SwaggerSupportSyntax.OperationBuilder
import org.scalatra.swagger.{DataType, Swagger, SwaggerSupport}
import org.scalatra.util.ValueReader
import org.xml.sax.SAXParseException
import siirto.{SchemaDefinition, ValidXml, XMLValidator}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.Try
import scala.xml.Elem
import scalaz._

class ImportBatchResource(eraOrgRekisteri: ActorRef,
                          eraRekisteri: ActorRef,
                          orgsActor: OrganisaatioActorRef,
                          parameterActor: ParametritActorRef,
                          config: Config,
                          queryMapper: (Map[String, String]) => Query[ImportBatch])
                         (externalIdField: String,
                          batchType: String,
                          dataField: String,
                          excelConverter: XmlConverter,
                          schema: SchemaDefinition,
                          imports: SchemaDefinition*)
                         (implicit sw: Swagger, s: Security, system: ActorSystem, mf: Manifest[ImportBatch], cf: Manifest[ImportBatchCommand])
    extends HakurekisteriResource[ImportBatch, ImportBatchCommand](eraRekisteri, queryMapper) with ImportBatchSwaggerApi with HakurekisteriCrudCommands[ImportBatch, ImportBatchCommand] with SecuritySupport with FileUploadSupport with IncidentReporting {

  override val logger: LoggingAdapter = Logging.getLogger(system, this)

  val maxFileSize = 50 * 1024 * 1024L
  val storageDir = config.tiedonsiirtoStorageDir

  configureMultipartHandling(MultipartConfig(
    maxFileSize = Some(maxFileSize)
  ))

  logger.info(s"storageDir: $storageDir")

  val validator = new ValidXml(schema, imports:_*)

  val schemaCache = (schema +: imports).map((sd) => sd.schemaLocation -> sd.schema).toMap


  registerCommand[ImportBatchCommand](ImportBatchCommand(externalIdField,
                                                         batchType,
                                                         dataField,
                                                         excelConverter,
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

  override def createEnabled(resource: ImportBatch, user: Option[User]) = (parameterActor.actor ? IsSendingEnabled(batchType)).mapTo[Boolean]

  override def updateEnabled(resource: ImportBatch, user: Option[User]) = createEnabled(resource, user)

  override def notEnabled = ResourceNotEnabledException

  private def parentOidToChildOids(parentOid: String): Future[Seq[String]] = {
    (orgsActor.actor ? GetChildOids(parentOid)).map{
      case Some(childOids: ChildOids) =>
        childOids.oids
      case _ => Seq()
    }
  }
  private def parentOidsToChildOidsIncludingParentOids(parentOids: Set[String]): Future[Set[String]] = {
    Future.sequence(parentOids.map(parentOidToChildOids)).map(_.flatten ++ parentOids)
  }

  def userImportBatchOrgs = {
    val user = getUser
    (user, user.orgsFor("READ", "ImportBatch"))
  }
  def isAdmin(orgs: Set[String]) = orgs.contains(Oids.ophOrganisaatioOid)

  get("/:id", operation(read)) {
    val id = UUID.fromString(params("id"))
    userImportBatchOrgs match {
      case (user, orgs) if isAdmin(orgs) =>
        readResource(AuthorizedRead(id, user))
      case (user, orgs) =>
        val hasOrganizationAccess: Future[AnyRef] = parentOidsToChildOidsIncludingParentOids(orgs).flatMap(childOrgs => eraOrgRekisteri ? QueryImportBatchReferences(childOrgs)).map{
          case ReferenceResult(references) if references.contains(id) =>
            AuthorizedReadWithOrgsChecked(id, user)
          case _ =>
            AuthorizedRead(id, user)
        }
        val success: (Any) => AnyRef = {
          case Some(data) => Ok(data)
          case None => NotFound()
          case data => Ok(data)
        }

        new FutureActorResult(hasOrganizationAccess,success)
    }
  }

  get("/schema", operation(schemaOperation)) {
    MovedPermanently(request.getRequestURL.append("/").append(schema.schemaLocation).toString)
  }

  get("/withoutdata", operation(withoutdata)) {
    userImportBatchOrgs match {
      case (user, orgs) if isAdmin(orgs) =>
        new AsyncResult() {
          override implicit def timeout: Duration = 60.seconds
          override val is = eraRekisteri.?(AllBatchStatuses)(60.seconds)
        }
      case (user, orgs) =>
        new AsyncResult() {
          override implicit def timeout: Duration = 60.seconds
          override val is =
            parentOidsToChildOidsIncludingParentOids(orgs).flatMap(childOrgs => eraOrgRekisteri ? QueryImportBatchReferences(childOrgs)) flatMap {
              case ReferenceResult(references) =>
                eraRekisteri.?(BatchesByReference(references))(60.seconds)
              case _ =>
                Future.failed(new RuntimeException(s"Unable to get batch references for organisations ${orgs}!"))
            }
        }
    }
  }

  get("/schema/:schema", operation(schemaOperation)) {
    schemaCache.get(params("schema")).fold(NotFound("not found")) {
      contentType = "application/xml"
      Ok(_)
    }
  }

  get("/isopen", operation(isopen)) {
    new AsyncResult() {
      override implicit def timeout: Duration = 120.seconds
      override val is = createEnabled(null, null).map(TiedonsiirtoOpen)
    }
  }

  post("/reprocess/:id", operation(reprocess)) {
    val user = getUser
    if (!user.orgsFor("WRITE", "ImportBatch").contains(Oids.ophOrganisaatioOid)) throw UserNotAuthorized("access not allowed")
    else new AsyncResult() {
      override implicit def timeout: Duration = 60.seconds
      val id = Try(UUID.fromString(params("id"))).get
      logger.debug(s"starting to reprocess $id")
      override val is = eraRekisteri.?(Reprocess(id))(60.seconds)
    }
  }

  incident {
    case ResourceNotEnabledException => (id) => NotFound(IncidentReport(id, "tiedonsiirto not open at the moment"))
    case t: WrongBatchStateException => (id) => BadRequest(IncidentReport(id, "illegal state for reprocessing"))
    case BatchNotFoundException => (id) => NotFound(IncidentReport(id, "batch not found for reprocessing"))
    case t: NotFoundException => (id) => NotFound(IncidentReport(id, "resource not found"))
    case t: MalformedResourceException => (id) => BadRequest(IncidentReport(incidentId = id, message = t.getMessage, validationErrors = t.errors.map(_.args.map(_.toString)).list.toList.reduce(_ ++ _)))
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

  import FileItemOperations._

  val tsFormat = "yyyyMMddHHmmssSSS"
  private def saveFiles(valid: Boolean)(implicit request: HttpServletRequest): Map[String, File] = {
    val ts = new SimpleDateFormat(tsFormat).format(new Date())
    val filename =
      if (valid) s"$storageDir/${ts}_${UUID.randomUUID()}"
      else s"$storageDir/${ts}_${UUID.randomUUID()}_invalid"
    if (multipart(request)) {
      fileMultiParams.get(dataField).getOrElse(Seq.empty[FileItem]).map((f: FileItem) => {
        val newFile = new File(s"$filename.${f.extension.getOrElse("unknown")}")
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
    val command =
      if (multipart(request))
        newCommand.bindTo[Map[String, FileItem], FileItem](
          fileParams,
          multiParams(request),
          request.headers
        )(files => new FileItemMapValueReader(files), manifest[FileItem], implicitly[MultiParams => ValueReader[MultiParams, Seq[String]]])
      else newCommand.bindTo(params(request) + (dataField -> request.body), multiParams(request), request.headers)

    saveFiles(command.isValid).foreach(entry => {
      logger.info(s"received ${if (command.isValid) "a valid" else "an invalid"} batch (${entry._1}) from ${getUser.username}, saving to storage as ${entry._2.getName}")
    })

    command
  }
}

case class ImportBatchCommand(externalIdField: String,
                              batchType: String,
                              dataField: String,
                              converter: XmlConverter,
                              validator: XMLValidator[ValidationNel[(String, SAXParseException), Elem], NonEmptyList[(String, SAXParseException)], Elem])
  extends HakurekisteriCommand[ImportBatch] {

  implicit val valid = validator

  override implicit val excelConverter = converter

  val data: Field[Elem] = asType[Elem](dataField).required.validateSchema

  override def toResource(user: String): ImportBatch =
    ImportBatch(
      data.value.get,
      data.value.flatMap(
        elem => (elem \ externalIdField).collectFirst { case e: Elem => e.text }
      ),
      batchType,
      user,
      BatchState.READY,
      ImportStatus()
    )
}

trait ImportBatchSwaggerApi extends SwaggerSupport with OldSwaggerSyntax {
  protected def applicationDescription: String = "Perustietojen tiedonsiirto"

  registerModel(Model("ImportBatch", "ImportBatch", Seq[ModelField](
    ModelField("externalId", "lähettäjän määrittämä tunniste, luetaan tiedoston elementistä 'eranTunniste'", DataType.String, required = false),
    ModelField("batchType", "lähetyksen tyyppi", DataType.String, Some("perustiedot")),
    ModelField("data", "lähetetty data", DataType.String)
  ).map(t => (t.name, t)).toMap))

  val update: OperationBuilder = apiOperation[ImportBatch]("päivitäSiirto")
    .summary("päivittää olemassa olevaa siirtoa ja palauttaa sen tiedot")
    .parameter(pathParam[String]("id").description("siirron uuid").required)
    .parameter(bodyParam[ImportBatch]("siirto").description("päivitettävä siirto").required)

  val delete: OperationBuilder = apiOperation[ImportBatch]("poistaSiirto")
    .summary("poistaa olemassa olevan siirron")
    .parameter(pathParam[String]("id").description("siirron uuid").required)

  val read: OperationBuilder = apiOperation[ImportBatch]("haeSiirto")
    .summary("hakee siirron tiedot")
    .parameter(pathParam[String]("id").description("siirron uuid").required)

  val create: OperationBuilder = apiOperation[ImportBatch]("lisääSiirto")
    .summary("vastaanottaa tiedoston")
    .notes("Vastaanottaa XML-tiedoston joko lomakkeen kenttänä multipart-koodattuna (kentän nimi 'data') tai XML-muodossa requestin bodyssä. " +
      "Tiedoston voi lähettää esimerkiksi curl-ohjelmaa käyttäen näin: " +
      "<pre>" +
      "<code class='bash'>$ curl -F \"data=@perustiedot.xml\" -H \"CasSecurityTicket: ST-tiketti\" https://testi.virkailija.opintopolku.fi/suoritusrekisteri/rest/v1/siirto/perustiedot</code>" +
      "</pre> " +
      "<br /><a href='/suoritusrekisteri/rest/v1/siirto/perustiedot/schema'>Perustietojen XML-skeema</a>")
    .consumes("application/xml", "multipart/form-data")
    .parameter(bodyParam[String].description("XML-tiedosto").required)
  val query: OperationBuilder = apiOperation[ImportBatch]("haeSiirrot")
    .summary("näyttää kaikki siirrot")
    .notes("Näyttää kaikki siirrot.")

  val isopen: OperationBuilder = apiOperation[TiedonsiirtoOpen]("onkoAvoinna")
    .summary("näyttää onko tiedonsiirto avoinna")
    .notes("Näyttää onko tiedonsiirto avoinna.")

  val withoutdata: OperationBuilder = apiOperation[Seq[ImportBatch]]("withoutdata")
    .summary("näyttää siirrot ilman tiedostoja")
    .notes("Näyttää siirrot ilman tiedostoja. Eroaa haeSiirrot-operaatiosta siten, että data-kenttä on tyhjä.")

  val schemaOperation: OperationBuilder = apiOperation[String]("schema")
    .summary("näyttää siirron validointiin käytettävän XML-skeeman")

  val reprocess: OperationBuilder = apiOperation[UUID]("reprocess")
    .summary("asettaa siirron uudelleenkäsiteltäväksi")
}

object EmptyFile extends FileItem(EmptyPart)

object EmptyPart extends Part {
  import scala.collection.JavaConverters._
  private val data: Array[Byte] = new Array[Byte](0)
  override def getInputStream: InputStream = new ByteArrayInputStream(data)
  override def getSubmittedFileName: String = ""
  override def getHeaderNames: util.Collection[String] = List().asJava
  override def getName: String = ""
  override def getSize: Long = data.length
  override def getHeaders(name: String): util.Collection[String] = List().asJava
  override def delete(): Unit = {}
  override def write(fileName: String): Unit = {}
  override def getContentType: String = "text/plain"
  override def getHeader(name: String): String = null
}

case class TiedonsiirtoOpen(open: Boolean)
