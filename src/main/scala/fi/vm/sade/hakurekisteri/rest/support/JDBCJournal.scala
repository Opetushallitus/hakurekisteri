package fi.vm.sade.hakurekisteri.rest.support

import scala.slick.lifted.{ProvenShape, ShapedValue, TableQuery}
import fi.vm.sade.hakurekisteri.storage.repository.{Updated, Deleted, Delta, Journal}
import scala.slick.driver.JdbcDriver.simple._
import scala.slick.lifted
import scala.compat.Platform
import fi.vm.sade.hakurekisteri.storage.Identified
import scala.language.existentials
import org.slf4j.LoggerFactory
import scala.slick.jdbc.meta.MTable


class JDBCJournal[R <: Resource[I], I, T <: JournalTable[R,I, _]](val table: TableQuery[T])(implicit val db: Database) extends Journal[R,  I] {

  val log = LoggerFactory.getLogger(getClass)

  lazy val tableName = table.baseTableRow.tableName

  db withSession(
    implicit session =>
      if (MTable.getTables(tableName).list().isEmpty) {
        table.ddl.create
      }
    )


  log.debug(s"started ${getClass.getSimpleName} with table $tableName")

  override def addModification(o: Delta[R, I]): Unit = db withSession(
    implicit session =>
      table += o
    )

  override def journal(latestQuery: Option[Long]): Seq[Delta[R, I]] =  latestQuery match  {
    case None =>
      db withSession {
        implicit session =>
          latestResources.list

      }
    case Some(latest) =>

      db withSession {
        implicit session =>
          table.filter(_.inserted >= latest).sortBy(_.inserted.asc).list
      }


  }

  def latestResources = {
    val latest = for {
      (id, resource) <- table.groupBy(_.resourceId)
    } yield (id, resource.map(_.inserted).max)

    val result = for {
      delta <- table
      (id, timestamp) <- latest
      if delta.resourceId === id && delta.inserted === timestamp.getOrElse(0)

    } yield delta

    result.sortBy(_.inserted.asc)
  }


}

abstract class JournalTable[R <: Resource[I], I, ResourceRow](tag: Tag, name: String) extends Table[Delta[R,I]](tag, name) {

  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def resourceId = column[String]("resource_id")

  def source = column[String]("source")
  def inserted = column[Long]("inserted")
  def deleted = column[Boolean]("deleted")
  val journalEntryShape = (resourceId, source, inserted, deleted).shaped
  type ShapedJournalRow = (lifted.Column[String], lifted.Column[String], lifted.Column[Long], lifted.Column[Boolean])
  type JournalRow = (String, String, Long, Boolean)

  def getId(serialized: String): I

  val resource: ResourceRow => R

  def delta(resourceId: String, source: String, inserted: Long, deleted: Boolean)(resourceData:ResourceRow):Delta[R, I] =
    if (deleted)
      Deleted(getId(resourceId), source)
    else
    {
      val resource1 = resource(resourceData)
      Updated(resource1.identify(getId(resourceId)))
    }


  def deltaShaper(j: (String, String, Long, Boolean), rd: ResourceRow): Delta[R, I] = (delta _).tupled(j)(rd)

  val deletedValues: ResourceRow

  def rowShaper(d: Delta[R, I]) = d match {
    case Deleted(id, source) => Some((id.toString, source, Platform.currentTime, true), deletedValues)
    case Updated(r: R with Identified[I]) => row(r).map(updateRow(r))

  }


  def updateRow(r: R with Identified[I])(resourceData: ResourceRow) = ((r.id.toString, r.asInstanceOf[R].source, Platform.currentTime, false), resourceData)

  def row(resource: R): Option[ResourceRow]
  def resourceShape: ShapedValue[_, ResourceRow]


  val combinedShape = journalEntryShape zip resourceShape


  def * : ProvenShape[Delta[R, I]] = {
    combinedShape <> ((deltaShaper _).tupled, rowShaper)
  }


}
