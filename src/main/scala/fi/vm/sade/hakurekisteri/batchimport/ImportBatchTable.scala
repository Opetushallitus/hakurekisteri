package fi.vm.sade.hakurekisteri.batchimport

import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriDriver, JournalTable}
import java.util.UUID
import HakurekisteriDriver.simple._

import scala.xml.Elem

object ImportBatchTable {
  type ImportBatchRow = (Elem, Option[String], String, String)
}

import ImportBatchTable._

class ImportBatchTable(tag: Tag) extends JournalTable[ImportBatch, UUID, ImportBatchRow](tag, "import_batch"){
  def data = column[Elem]("data")
  def externalId = column[Option[String]]("external_id")
  def batchType = column[String]("batch_type")

  override def resourceShape = (data, externalId, batchType, source).shaped
  override def row(resource: ImportBatch): Option[ImportBatchTable.ImportBatchRow] = ImportBatch.unapply(resource)
  override val deletedValues: (String) => ImportBatchTable.ImportBatchRow = (lahde) => (<emptybatch/>, None, "deleted", lahde)
  override val resource: (ImportBatchTable.ImportBatchRow) => ImportBatch = (ImportBatch.apply _).tupled
  override val extractSource: (ImportBatchTable.ImportBatchRow) => String = _._4
}
