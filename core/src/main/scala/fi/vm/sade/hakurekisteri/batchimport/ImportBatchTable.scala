package fi.vm.sade.hakurekisteri.batchimport

import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriDriver, JournalTable}
import java.util.UUID
import HakurekisteriDriver.simple._

import scala.xml.Elem


import BatchState.BatchState

object ImportBatchTable {
  type ImportBatchRow = (Elem, Option[String], String, String, BatchState, ImportStatus)
}

import ImportBatchTable._

import ImportBatchImplicits._

class ImportBatchTable(tag: Tag) extends JournalTable[ImportBatch, UUID, ImportBatchRow](tag, "import_batch") {
  def data = column[Elem]("data")
  def externalId = column[Option[String]]("external_id")
  def batchType = column[String]("batch_type")
  def state = column[BatchState]("state")
  def status = column[ImportStatus]("status")

  def eIndex = index("i_import_batch_external_id", externalId)
  def bIndex = index("i_import_batch_batch_type", batchType)
  def sIndex = index("i_import_batch_state", state)

  override def resourceShape = (data, externalId, batchType, source, state, status).shaped
  override def row(resource: ImportBatch): Option[ImportBatchTable.ImportBatchRow] = ImportBatch.unapply(resource)
  override val deletedValues: (String) => ImportBatchTable.ImportBatchRow = (lahde) => (<emptybatch/>, None, "deleted", lahde, BatchState.READY, ImportStatus())
  override val resource: (ImportBatchTable.ImportBatchRow) => ImportBatch = (ImportBatch.apply _).tupled
  override val extractSource: (ImportBatchTable.ImportBatchRow) => String = _._4
}
