package fi.vm.sade.hakurekisteri.batchimport

import java.util.UUID
import fi.vm.sade.hakurekisteri.batchimport.ImportBatchOrgTable.ImportBatchOrgsRow
import slick.ast.{FieldSymbol, Node}
import slick.driver.PostgresDriver
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._

import scala.compat.Platform

object ImportBatchOrgTable {
  type ImportBatchOrgsRow = (UUID, String, Long)
}

case class ImportBatchOrgs(resourceId: UUID, oids: Set[String])
case class ImportBatchOrg(resourceId: UUID, oid: String)

class ImportBatchOrgTable(tag: Tag) extends Table[ImportBatchOrgsRow](tag, "import_batch_org") {
  def resourceId = column[UUID]("resource_id")
  def oid = column[String]("oid")
  def inserted = column[Long]("inserted")

  def pk = primaryKey("pk_importbatchorg", (resourceId, oid))
  def * = (resourceId, oid, inserted)
}
