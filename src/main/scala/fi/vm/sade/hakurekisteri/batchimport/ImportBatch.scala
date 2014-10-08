package fi.vm.sade.hakurekisteri.batchimport

import fi.vm.sade.hakurekisteri.rest.support.Resource
import java.util.UUID
import fi.vm.sade.hakurekisteri.storage.{UUIDIdentifier,  Identified}
import org.json4s.JsonAST.JValue


case class ImportBatch(data: JValue, externalId: Option[String] = None, batchType: String, source: String) extends Resource[UUID]  {

  override def identify(identifier: UUID): this.type with Identified[UUID] = new ImportBatch(data,externalId,batchType,source) with Identified[UUID] {
    val id: UUID = identifier
  }.asInstanceOf[this.type with Identified[UUID]]

}


object ImportBatch extends UUIDIdentifier[ImportBatch]



