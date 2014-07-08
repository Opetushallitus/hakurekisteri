package fi.vm.sade.hakurekisteri.henkilo

import fi.vm.sade.hakurekisteri.storage.repository.{Deleted, Delta, JDBCJournal}
import scala.slick.lifted.ColumnOrdered
import fi.vm.sade.hakurekisteri.storage.Identified
import scala.slick.driver.JdbcDriver
import scala.slick.driver.JdbcDriver.simple._
import java.util.UUID
import scala.slick.jdbc.meta.MTable
import org.json4s.jackson.Serialization.{read, write}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import scala.compat.Platform
import scala.slick.lifted


class HenkiloJournal(database: Database) extends JDBCJournal[Henkilo, HenkiloTable, ColumnOrdered[Long], UUID] with HakurekisteriJsonSupport {
  override def delta(row: HenkiloTable#TableElementType): Delta[Henkilo, UUID] =
    row match {
      case (resourceId, _, _, true) => Deleted(UUID.fromString(resourceId))
      case (resourceId, henkilo, inserted, deleted) => fi.vm.sade.hakurekisteri.storage.repository.Updated(read[Henkilo](henkilo).identify(UUID.fromString(resourceId)))
    }
  override def update(o: Henkilo with Identified[UUID]): HenkiloTable#TableElementType = (o.id.toString, write(o), Platform.currentTime, false)
  override def delete(id:UUID) = currentState(id) match
  { case (resourceId, henkilo, _, _)  =>
      (resourceId, henkilo, Platform.currentTime,true)}

  val henkilot = TableQuery[HenkiloTable]
    database withSession(
      implicit session =>
        if (MTable.getTables("henkilo").list().isEmpty) {
          henkilot.ddl.create
        }
    )


  override def newest: (HenkiloTable) => ColumnOrdered[Long] = _.inserted.desc

  override def filterByResourceId(id: UUID): (HenkiloTable) => Column[Boolean] = _.resourceId === id.toString

  override val table = henkilot
  override val db: JdbcDriver.simple.Database = database
  override val sortColumn = (h: HenkiloTable) => h.inserted

  override def timestamp(resource: HenkiloTable): lifted.Column[Long] = resource.inserted

  override def timestamp(resource: HenkiloTable#TableElementType): Long = resource._3

  override val idColumn: (HenkiloTable) => Column[String] = _.resourceId

  override def latestResources  = {
    val latest = for {
      (id, resource) <- table.groupBy(idColumn)
    } yield (id, resource.map(sortColumn).max)

    val result = for {
      delta <- table
      (id, timestamp) <- latest
      if idColumn(delta) === id && sortColumn(delta) === timestamp.getOrElse(0)

    } yield delta

    result.sortBy(sortColumn(_).asc)
  }
}

class HenkiloTable(tag: Tag) extends Table[(String, String, Long, Boolean)](tag, "henkilo") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def resourceId = column[String]("resource_id")
  def henkilo = column[String]("henkilo", O.DBType("CLOB"))
  def inserted = column[Long]("inserted")
  def deleted = column[Boolean]("deleted")
  def * = (resourceId, henkilo, inserted, deleted)
}
