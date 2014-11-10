package fi.vm.sade.hakurekisteri.batchimport

import fi.vm.sade.hakurekisteri.rest.support._
import akka.actor.ActorSystem
import org.scalatra.util.ValueReader
import org.scalatra.util.conversion.TypeConverter
import org.scalatra.{DefaultValues, DefaultValue}
import org.scalatra.swagger.Swagger
import org.scalatra.commands._

import scala.util.Try
import scala.xml.{XML, Elem}


abstract class ImportBatchResource(authorizedRegisters: Registers,
                                   queryMapper: (Map[String, String]) => Query[ImportBatch])
                                  (externalIdField: String,
                                   batchType: String,
                                   dataField: String,
                                   validations: (String, Elem => Boolean)*)
                                  (implicit sw: Swagger, system: ActorSystem, mf: Manifest[ImportBatch], cf: Manifest[ImportBatchCommand])
    extends HakurekisteriResource[ImportBatch, ImportBatchCommand](authorizedRegisters.eraRekisteri, queryMapper) with HakurekisteriCrudCommands[ImportBatch, ImportBatchCommand] with SpringSecuritySupport {

  registerCommand[ImportBatchCommand](ImportBatchCommand(externalIdField,
                                                         batchType,
                                                         dataField,
                                                         validations:_*))
}

case class ImportBatchCommand(externalIdField: String, batchType: String, dataField: String, validations: (String, Elem => Boolean)*) extends HakurekisteriCommand[ImportBatch] {

  val validators =  validations.map{
    case (messageFormat, validate ) => BindingValidators.validate(validate, messageFormat)
  }.toList
  val externalId: Field[Option[String]] = binding2field(asType[Option[String]](externalIdField).optional)
  val data: Field[Elem] = binding2field(asType[Elem](dataField).validateWith(validators:_*))

  override def toResource(user: String): ImportBatch = ImportBatch(data.value.get, externalId.value.get, batchType, user)
}

