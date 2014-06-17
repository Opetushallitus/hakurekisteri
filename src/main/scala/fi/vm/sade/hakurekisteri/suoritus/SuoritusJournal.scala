package fi.vm.sade.hakurekisteri.suoritus

import scala.slick.driver.JdbcDriver.simple._
import fi.vm.sade.hakurekisteri.storage.repository.{Updated, Delta, Deleted, JDBCJournal}
import scala.slick.lifted.ColumnOrdered
import fi.vm.sade.hakurekisteri.storage.Identified
import org.joda.time.LocalDate
import scala.slick.jdbc.meta.MTable
import scala.slick.driver.JdbcDriver
import java.util.UUID
import scala.compat.Platform
import scala.slick.lifted

class SuoritusJournal(database: Database) extends JDBCJournal[Suoritus, SuoritusTable, ColumnOrdered[Long], UUID] {
  override def delta(row: SuoritusTable#TableElementType): Delta[Suoritus, UUID] =
    row match {
      case (resourceId, _, _, _, _, _, _, _, _, true) => Deleted(UUID.fromString(resourceId))
      case (resourceId, komo, myontaja, tila, valmistuminen, henkiloOid, yks, suoritusKieli, _, _) => Updated(Suoritus(komo, myontaja, tila, LocalDate.parse(valmistuminen), henkiloOid, yksilollistaminen.withName(yks), suoritusKieli).identify(UUID.fromString(resourceId)))
    }


  override def update(o: Suoritus with Identified[UUID]): SuoritusTable#TableElementType = (o.id.toString, o.komo, o.myontaja, o.tila, o.valmistuminen.toString, o.henkiloOid, o.yksilollistaminen.toString, o.suoritusKieli, Platform.currentTime, false)
  override def delete(id:UUID) = currentState(id) match
  { case (resourceId, komo, myontaja, tila, valmistuminen, henkiloOid, yksilollistaminen, suoritusKieli, _, _) =>
      (resourceId, komo, myontaja, tila, valmistuminen, henkiloOid, yksilollistaminen, suoritusKieli, Platform.currentTime,true)}


  val suoritukset = TableQuery[SuoritusTable]
  database withSession(
    implicit session =>
      if (MTable.getTables("suoritus").list().isEmpty) {
        suoritukset.ddl.create
      }
    )

  override def newest: (SuoritusTable) => ColumnOrdered[Long] = _.inserted.desc

  override def filterByResourceId(id: UUID): (SuoritusTable) => Column[Boolean] = _.resourceId === id.toString

  override val table = suoritukset
  override val db: JdbcDriver.simple.Database = database
  override val journalSort = (o: SuoritusTable) => o.inserted.asc

  override def timestamp(resource: SuoritusTable): lifted.Column[Long] = resource.inserted

  override def timestamp(resource: SuoritusTable#TableElementType): Long = resource._9
}

class SuoritusTable(tag: Tag) extends Table[(String, String, String, String, String, String, String, String, Long, Boolean)](tag, "suoritus") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def resourceId = column[String]("resource_id")
  def komo = column[String]("komo")
  def myontaja = column[String]("myontaja")
  def tila = column[String]("tila")
  def valmistuminen = column[String]("valmistuminen")
  def henkiloOid = column[String]("henkilo_oid")
  def yksilollistaminen = column[String]("yksilollistaminen")
  def suoritusKieli = column[String]("suoritus_kieli")
  def inserted = column[Long]("inserted")
  def deleted = column[Boolean]("deleted")
  // Every table needs a * projection with the same type as the table's type parameter
  def * = (resourceId, komo, myontaja, tila, valmistuminen, henkiloOid, yksilollistaminen, suoritusKieli, inserted, deleted)
}