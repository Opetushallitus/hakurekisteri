package fi.vm.sade.hakurekisteri.suoritus

import scala.slick.driver.JdbcDriver.simple._
import fi.vm.sade.hakurekisteri.storage.repository.JDBCJournal
import scala.slick.lifted.ColumnOrdered
import fi.vm.sade.hakurekisteri.storage.Identified
import org.joda.time.LocalDate
import scala.slick.jdbc.meta.MTable
import scala.slick.driver.JdbcDriver
import java.util.UUID

class SuoritusJournal(database: Database) extends JDBCJournal[Suoritus, SuoritusTable, ColumnOrdered[Long]] {
  override def toResource(row: SuoritusTable#TableElementType): Suoritus with Identified = Suoritus(row._2, row._3, row._4, LocalDate.parse(row._5), row._6, yksilollistaminen.withName(row._7), row._8).identify(UUID.fromString(row._1))
  override def toRow(o: Suoritus with Identified): SuoritusTable#TableElementType = (o.id.toString, o.komo, o.myontaja, o.tila, o.valmistuminen.toString, o.henkiloOid, o.yksilollistaminen.toString, o.suoritusKieli, System.currentTimeMillis())

  val opiskelijat = TableQuery[SuoritusTable]
  database withSession(
    implicit session =>
      if (MTable.getTables("suoritus").list().isEmpty) {
        opiskelijat.ddl.create
      }
    )

  override val table = opiskelijat
  override val db: JdbcDriver.simple.Database = database
  override val journalSort = (o: SuoritusTable) => o.inserted.asc
}

class SuoritusTable(tag: Tag) extends Table[(String, String, String, String, String, String, String, String, Long)](tag, "suoritus") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def resourceId = column[String]("resource_id")
  def komo = column[String]("komo")
  def myontaja = column[String]("myontaja")
  def tila = column[String]("tila")
  def valmistuminen = column[String]("luokkataso")
  def henkiloOid = column[String]("henkilo_oid")
  def yksilollistaminen = column[String]("yksilollistaminen")
  def suoritusKieli = column[String]("suoritus_kieli")
  def inserted = column[Long]("inserted")
  // Every table needs a * projection with the same type as the table's type parameter
  def * = (resourceId, komo, myontaja, tila, valmistuminen, henkiloOid, yksilollistaminen, suoritusKieli, inserted)
}