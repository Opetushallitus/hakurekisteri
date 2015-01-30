package fi.vm.sade.hakurekisteri.batchimport

import fi.vm.sade.hakurekisteri.rest.support.UUIDResource
import java.util.UUID
import fi.vm.sade.hakurekisteri.storage.Identified
import org.joda.time.DateTime

import scala.xml.Elem

import BatchState.BatchState


case class ImportStatus(sentTime: DateTime = new DateTime(),
                        processedTime: Option[DateTime] = None,
                        messages: Map[String, Set[String]] = Map(),
                        successRows: Option[Int] = None,
                        failureRows: Option[Int] = None,
                        totalRows: Option[Int] = None,
                        savedReferences: Option[Map[String, Map[String, String]]] = None)

case class ImportBatch(data: Elem, externalId: Option[String] = None, batchType: String, source: String, state: BatchState = BatchState.READY, status: ImportStatus) extends UUIDResource[ImportBatch]  {
  override def identify(identifier: UUID): ImportBatch with Identified[UUID] = new IdentifiedImportBatch(this, identifier)

  private[ImportBatch] case class ImportUnique(source: String, batchType: String, externalId: Option[String])

  override val core: AnyRef = ImportUnique(source, batchType, externalId)
}

class IdentifiedImportBatch(b: ImportBatch, identifier: UUID) extends ImportBatch(b.data, b.externalId, b.batchType, b.source, b.state, b.status) with Identified[UUID] {
  val id: UUID = identifier
}
