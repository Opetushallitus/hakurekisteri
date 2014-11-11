package fi.vm.sade.hakurekisteri.batchimport

import javax.servlet.http.HttpServletRequest

import akka.actor.{ActorRef, ActorSystem}
import fi.vm.sade.hakurekisteri.rest.support._
import org.scalatra.commands._
import org.scalatra.swagger.{DataType, SwaggerSupport, Swagger}
import org.scalatra.swagger.SwaggerSupportSyntax.OperationBuilder

import scala.xml.Elem


class ImportBatchResource(eraRekisteri: ActorRef,
                                   queryMapper: (Map[String, String]) => Query[ImportBatch])
                                  (externalIdField: String,
                                   batchType: String,
                                   dataField: String,
                                   validations: (String, Elem => Boolean)*)
                                  (implicit sw: Swagger, system: ActorSystem, mf: Manifest[ImportBatch], cf: Manifest[ImportBatchCommand])
    extends HakurekisteriResource[ImportBatch, ImportBatchCommand](eraRekisteri, queryMapper) with ImportBatchSwaggerApi with HakurekisteriCrudCommands[ImportBatch, ImportBatchCommand] with SpringSecuritySupport {

  registerCommand[ImportBatchCommand](ImportBatchCommand(externalIdField,
                                                         batchType,
                                                         dataField,
                                                         validations:_*))

  override protected def bindCommand[T <: CommandType](newCommand: T)(implicit request: HttpServletRequest, mf: Manifest[T]): T  = {
    newCommand.bindTo(params(request) + (dataField -> request.body), multiParams(request), request.headers)
  }
}

case class ImportBatchCommand(externalIdField: String, batchType: String, dataField: String, validations: (String, Elem => Boolean)*) extends HakurekisteriCommand[ImportBatch] {

  val validators =  validations.map{
    case (messageFormat, validate ) => BindingValidators.validate(validate, messageFormat)
  }.toList
  val externalId: Field[Option[String]] = binding2field(asType[Option[String]](externalIdField).optional)
  val data: Field[Elem] = binding2field(asType[Elem](dataField).validateWith(validators:_*))

  override def toResource(user: String): ImportBatch = ImportBatch(data.value.get, externalId.value.get, batchType, user)
}


trait ImportBatchSwaggerApi extends SwaggerSupport with OldSwaggerSyntax {


  protected def applicationDescription: String = "foo"

  registerModel(Model("ImportBatch", "ImportBatch", Seq[ModelField](ModelField("foo", "foo", DataType.String)).map(t => (t.name, t)).toMap))

  val update: OperationBuilder = apiOperation[ImportBatch]("foo")
  val delete: OperationBuilder = apiOperation[ImportBatch]("foo1")
  val read: OperationBuilder = apiOperation[ImportBatch]("foo2")
  val create: OperationBuilder = apiOperation[ImportBatch]("foo3")
  val query: OperationBuilder = apiOperation[ImportBatch]("foo4")


}