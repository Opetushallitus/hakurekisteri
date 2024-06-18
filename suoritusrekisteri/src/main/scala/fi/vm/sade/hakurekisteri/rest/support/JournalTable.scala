package fi.vm.sade.hakurekisteri.rest.support

import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.storage.repository.{Deleted, Delta, Insert, Updated}
import slick.ast.BaseTypedType
import slick.lifted.{ProvenShape, ShapedValue}

import scala.compat.Platform
import scala.language.{existentials, postfixOps}

abstract class JournalTable[R <: Resource[I, R], I, ResourceRow](tag: Tag, name: String)(implicit
  val idType: BaseTypedType[I]
) extends Table[Delta[R, I]](tag, name) {
  def resourceId = column[I]("resource_id")(idType)
  def source = column[String]("source")
  def inserted = column[Long]("inserted")
  def deleted = column[Boolean]("deleted")
  def current = column[Boolean]("current", O.Default(false))
  def pk = primaryKey(s"pk_$name", (resourceId, inserted))

  type JournalRow = (I, Long, Boolean, Boolean)

  def resource: ResourceRow => R

  def extractSource: ResourceRow => String

  def deletedValues: String => ResourceRow

  def row(resource: R): Option[ResourceRow]

  def resourceShape: ShapedValue[_, ResourceRow]

  private def combinedShape = (resourceId, inserted, deleted, current).shaped zip resourceShape

  private object DeltaShaper {
    def apply(t: (JournalRow, ResourceRow)): Delta[R, I] = t match {
      case ((resourceId, _, deleted, _), resourceData) =>
        if (deleted) {
          Deleted(resourceId, extractSource(resourceData))
        } else {
          Updated(resource(resourceData).identify(resourceId))
        }
    }

    private def updateRow(r: R with Identified[I])(resourceData: ResourceRow) =
      ((r.id, Platform.currentTime, false, true), resourceData)

    def unapply(d: Delta[R, I]): Option[(JournalRow, ResourceRow)] = d match {
      case Deleted(id, source) =>
        Some((id, Platform.currentTime, true, true), deletedValues(source))
      case Updated(r) => row(r).map(updateRow(r))
      case Insert(r) =>
        throw new NotImplementedError("Insert deltas not implemented in JDBCJournal")
    }
  }

  def * : ProvenShape[Delta[R, I]] =
    ProvenShape.proveShapeOf(combinedShape <> (DeltaShaper.apply, DeltaShaper.unapply))
}
