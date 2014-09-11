package fi.vm.sade.hakurekisteri.opiskeluoikeus

import java.util.UUID

import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.storage.repository.{Updated, Deleted, Delta, JDBCJournal}
import org.joda.time.DateTime

import scala.compat.Platform
import scala.slick.driver.JdbcDriver
import scala.slick.driver.JdbcDriver.simple._
import scala.slick.jdbc.meta.MTable
import scala.slick.lifted
import scala.slick.lifted.ColumnOrdered

class OpiskeluoikeusJournal(database: Database) extends JDBCJournal[Opiskeluoikeus, OpiskeluoikeusTable, ColumnOrdered[Long], UUID] {
  override val db: JdbcDriver.simple.Database = database

  val opiskeluoikeudet = TableQuery[OpiskeluoikeusTable]
    database withSession(
      implicit session =>
        if (MTable.getTables("opiskeluoikeus").list().isEmpty) {
          opiskeluoikeudet.ddl.create
        }
    )


  override def update(resource: Opiskeluoikeus with Identified[UUID]): (String, Long, Option[Long], String, String, String, String, Long, Boolean) =
    (resource.id.toString, resource.aika.alku.getMillis, resource.aika.loppuOption.map(_.getMillis) , resource.henkiloOid, resource.komo, resource.myontaja, resource.source, Platform.currentTime, false)

  override def newest: (OpiskeluoikeusTable) => ColumnOrdered[Long] = _.inserted.desc

  override def latestResources: lifted.Query[OpiskeluoikeusTable, (String, Long, Option[Long], String, String, String, String, Long, Boolean)] = {
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

  override def filterByResourceId(id: UUID): (OpiskeluoikeusTable) => JdbcDriver.simple.Column[Boolean] = _.resourceId === id.toString

  override def delta(row: (String, Long, Option[Long], String, String, String, String, Long, Boolean)): Delta[Opiskeluoikeus, UUID] = row match {
    case (resourceId, _, _, _, _, _, source ,_, true) => Deleted(UUID.fromString(resourceId), source)
    case (resourceId: String, alkuPaiva: Long, loppuPaiva: Option[Long], henkiloOid: String, komo: String, myontaja: String, source, foo, _) => Updated(Opiskeluoikeus(new DateTime(alkuPaiva), loppuPaiva.map(new DateTime(_)), henkiloOid, komo, myontaja, source).identify(UUID.fromString(resourceId)))
  }

  override def delete(id: UUID, source: String): (String, Long, Option[Long], String, String, String, String, Long, Boolean) = currentState(id) match {
    case (resourceId, alkuPvm, loppuPvm, henkiloOid, komo, myontaja,_, _, _)  =>
      (resourceId, alkuPvm, loppuPvm, henkiloOid, komo, myontaja, source, Platform.currentTime, true)
  }

  override def timestamp(resource: OpiskeluoikeusTable): lifted.Column[Long] = resource.inserted

  override def timestamp(resource: (String, Long, Option[Long], String, String, String, String,  Long, Boolean)): Long = resource._8

  override val idColumn: (OpiskeluoikeusTable) => JdbcDriver.simple.Column[String] = _.resourceId
  override val sortColumn: (OpiskeluoikeusTable) => JdbcDriver.simple.Column[Long] = (o: OpiskeluoikeusTable) => o.inserted
  override val table: lifted.TableQuery[OpiskeluoikeusTable] = opiskeluoikeudet
}

class OpiskeluoikeusTable(tag: Tag) extends Table[(String, Long, Option[Long], String, String, String, String, Long, Boolean)](tag, "opiskeluoikeus") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def resourceId = column[String]("resource_id")
  def alkuPaiva = column[Long]("alku_paiva")
  def loppuPaiva = column[Option[Long]]("loppu_paiva")
  def henkiloOid = column[String]("henkilo_oid")
  def komo = column[String]("komo")
  def myontaja = column[String]("myontaja")
  def source = column[String]("source")
  def inserted = column[Long]("inserted")
  def deleted = column[Boolean]("deleted")
  // Every table needs a * projection with the same type as the table's type parameter
  def * = (resourceId, alkuPaiva, loppuPaiva, henkiloOid, komo, myontaja, source, inserted, deleted)
}