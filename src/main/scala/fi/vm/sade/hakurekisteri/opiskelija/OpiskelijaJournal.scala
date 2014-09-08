package fi.vm.sade.hakurekisteri.opiskelija

import fi.vm.sade.hakurekisteri.storage.repository.{Updated, Deleted, Delta, JDBCJournal}
import scala.slick.lifted.ColumnOrdered
import fi.vm.sade.hakurekisteri.storage.Identified
import org.joda.time.DateTime
import scala.slick.driver.JdbcDriver
import scala.slick.driver.JdbcDriver.simple._
import java.util.UUID
import scala.slick.jdbc.meta.MTable
import fi.vm.sade.hakurekisteri.henkilo.Henkilo
import org.json4s.jackson.Serialization._
import fi.vm.sade.hakurekisteri.storage.repository.Deleted
import fi.vm.sade.hakurekisteri.storage.repository.Updated
import scala.slick.lifted.ColumnOrdered
import scala.compat.Platform
import scala.slick.lifted

class OpiskelijaJournal(database: Database) extends JDBCJournal[Opiskelija, OpiskelijaTable, ColumnOrdered[Long], UUID] {
  override def delta(row: OpiskelijaTable#TableElementType): Delta[Opiskelija, UUID] = row match {
    case (resourceId, _, _, _, _, _, _, source,  _, true) => Deleted(UUID.fromString(resourceId), source)
    case (resourceId, oppilaitosOid, luokkataso, luokka, henkiloOid, alkuPaiva, loppuPaiva, source,  _, _) => Updated(Opiskelija(oppilaitosOid, luokkataso, luokka, henkiloOid,new DateTime(alkuPaiva), loppuPaiva.map(new DateTime(_)), source).identify(UUID.fromString(resourceId)))
  }

  override def update(o: Opiskelija with Identified[UUID]): OpiskelijaTable#TableElementType = (o.id.toString, o.oppilaitosOid, o.luokkataso, o.luokka, o.henkiloOid, o.alkuPaiva.getMillis, o.loppuPaiva.map(_.getMillis), o.source, Platform.currentTime, false)
  override def delete(id: UUID, source :String) = currentState(id) match {
    case (resourceId, oppilaitosOid, luokkataso, luokka, henkiloOid, alkuPaiva, loppuPaiva,_, _, _)  =>
      (resourceId, oppilaitosOid, luokkataso, luokka, henkiloOid, alkuPaiva, loppuPaiva, source, Platform.currentTime, true)
  }

  val opiskelijat = TableQuery[OpiskelijaTable]
    database withSession(
      implicit session =>
        if (MTable.getTables("opiskelija").list().isEmpty) {
          opiskelijat.ddl.create
        }
    )


  override def newest: (OpiskelijaTable) => ColumnOrdered[Long] = _.inserted.desc

  override def filterByResourceId(id: UUID): (OpiskelijaTable) => Column[Boolean] = _.resourceId === id.toString


  override val table = opiskelijat
  override val db: JdbcDriver.simple.Database = database
  override val sortColumn = (o: OpiskelijaTable) => o.inserted

  override def timestamp(resource: OpiskelijaTable): lifted.Column[Long] = resource.inserted

  override def timestamp(resource: OpiskelijaTable#TableElementType): Long = resource._9

  override val idColumn: (OpiskelijaTable) => Column[String] = _.resourceId

  override def latestResources = {
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

class OpiskelijaTable(tag: Tag) extends Table[(String, String, String, String, String, Long, Option[Long], String, Long, Boolean)](tag, "opiskelija") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def resourceId = column[String]("resource_id")
  def oppilaitosOid = column[String]("oppilaitos_oid")
  def luokkataso = column[String]("luokkataso")
  def luokka = column[String]("luokka")
  def henkiloOid = column[String]("henkilo_oid")
  def alkuPaiva = column[Long]("alku_paiva")
  def loppuPaiva = column[Option[Long]]("loppu_paiva")
  def source = column[String]("source")
  def inserted = column[Long]("inserted")
  def deleted = column[Boolean]("deleted")
  // Every table needs a * projection with the same type as the table's type parameter
  def * = (resourceId, oppilaitosOid, luokkataso, luokka, henkiloOid, alkuPaiva, loppuPaiva, source,  inserted, deleted)
}
