package fi.vm.sade.hakurekisteri.batchimport

import fi.vm.sade.hakurekisteri.rest.support.UUIDResource
import java.util.UUID
import fi.vm.sade.hakurekisteri.storage.Identified
import org.json4s.JsonAST.JValue


case class ImportBatch(data: JValue, externalId: Option[String] = None, batchType: String, source: String) extends UUIDResource[ImportBatch]  {

  override def identify(identifier: UUID): ImportBatch with Identified[UUID] = new IdentifiedImportBatch(this, identifier)

  override val core: AnyRef = externalId
}


class IdentifiedImportBatch(b: ImportBatch, identifier: UUID) extends ImportBatch(b.data,b.externalId,b.batchType,b.source) with Identified[UUID] {
  val id: UUID = identifier
}



