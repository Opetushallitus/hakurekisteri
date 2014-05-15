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

class OpiskelijaJournal(database: Database) extends JDBCJournal[Opiskelija, OpiskelijaTable, ColumnOrdered[Long]] {
  override def delta(row: OpiskelijaTable#TableElementType): Delta[Opiskelija] =
    row match {
      case (resourceId, _, _, _, _, _, _, _, true) => Deleted(UUID.fromString(resourceId))
      case (resourceId, oppilaitosOid, luokkataso, luokka, henkiloOid, alkuPaiva, loppuPaiva, _, _) => Updated(Opiskelija(oppilaitosOid, luokkataso, luokka, henkiloOid,new DateTime(alkuPaiva), loppuPaiva.map(new DateTime(_))).identify(UUID.fromString(resourceId)))
    }

  override def update(o: Opiskelija with Identified): OpiskelijaTable#TableElementType = (o.id.toString, o.oppilaitosOid, o.luokkataso, o.luokka, o.henkiloOid, o.alkuPaiva.getMillis, o.loppuPaiva.map(_.getMillis), Platform.currentTime, true)
  override def delete(id:UUID) = currentState(id) match
    { case (resourceId, oppilaitosOid, luokkataso, luokka, henkiloOid, alkuPaiva, loppuPaiva, _, _)  =>
      (resourceId, oppilaitosOid, luokkataso, luokka, henkiloOid, alkuPaiva, loppuPaiva, Platform.currentTime,true)}

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
  override val journalSort = (o: OpiskelijaTable) => o.inserted.asc
}

class OpiskelijaTable(tag: Tag) extends Table[(String, String, String, String, String, Long, Option[Long], Long, Boolean)](tag, "opiskelija") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def resourceId = column[String]("resource_id")
  def oppilaitosOid = column[String]("oppilaitos_oid")
  def luokkataso = column[String]("luokkataso")
  def luokka = column[String]("luokka")
  def henkiloOid = column[String]("henkilo_oid")
  def alkuPaiva = column[Long]("alku_paiva")
  def loppuPaiva = column[Option[Long]]("loppu_paiva")
  def inserted = column[Long]("inserted")
  def deleted = column[Boolean]("deleted")
  // Every table needs a * projection with the same type as the table's type parameter
  def * = (resourceId, oppilaitosOid, luokkataso, luokka, henkiloOid, alkuPaiva, loppuPaiva, inserted, deleted)
}
