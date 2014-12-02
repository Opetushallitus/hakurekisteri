package fi.vm.sade.hakurekisteri.batchimport

import fi.vm.sade.hakurekisteri.rest.support.UUIDResource
import java.util.UUID
import fi.vm.sade.hakurekisteri.storage.Identified

import scala.xml.Elem

import BatchState.BatchState


case class ImportBatch(data: Elem, externalId: Option[String] = None, batchType: String, source: String, state: BatchState = BatchState.READY) extends UUIDResource[ImportBatch]  {
  override def identify(identifier: UUID): ImportBatch with Identified[UUID] = new IdentifiedImportBatch(this, identifier)

  private[ImportBatch] case class ImportUnique(source: String, batchType: String, externalId: Option[String])

  override val core: AnyRef = ImportUnique(source, batchType, externalId)
}

class IdentifiedImportBatch(b: ImportBatch, identifier: UUID) extends ImportBatch(b.data, b.externalId, b.batchType, b.source, b.state) with Identified[UUID] {
  val id: UUID = identifier
}
