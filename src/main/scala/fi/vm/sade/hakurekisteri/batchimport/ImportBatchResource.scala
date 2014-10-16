package fi.vm.sade.hakurekisteri.batchimport

import fi.vm.sade.hakurekisteri.rest.support._
import akka.actor.ActorSystem
import org.scalatra.swagger.Swagger
import org.scalatra.commands._
import org.json4s.JsonAST.JValue


abstract class ImportBatchResource(authorizedRegisters: Registers,
                                   queryMapper: (Map[String,String]) => Query[ImportBatch])
                                  (externalIdField: String,
                                   batchType: String,
                                   dataField: String,
                                   validations: (String, JValue => Boolean)*)(implicit sw: Swagger, system: ActorSystem, mf: Manifest[ImportBatch],cf:Manifest[ImportBatchCommand]) extends  HakurekisteriResource[ImportBatch, ImportBatchCommand](authorizedRegisters.eraRekisteri, queryMapper)  with HakurekisteriCrudCommands[ImportBatch, ImportBatchCommand] with SpringSecuritySupport {

  registerCommand[ImportBatchCommand](ImportBatchCommand(externalIdField,
                                                         batchType,
                                                         dataField,
                                                         validations:_*))


}

case class ImportBatchCommand(externalIdField: String, batchType: String, dataField: String, validations: (String, JValue => Boolean)*) extends  HakurekisteriCommand[ImportBatch]{


  val validators =  validations.map{
    case (messageFormat, validate ) => BindingValidators.validate(validate, messageFormat)
  }.toList
  val externalId: Field[Option[String]] = asType[Option[String]](externalIdField).optional
  val data: Field[JValue] = asType[JValue](dataField).validateWith(validators:_*)

  override def toResource(user: String): ImportBatch = ImportBatch(data.value.get,externalId.value.get, batchType, user)
}

