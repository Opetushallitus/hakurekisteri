package fi.vm.sade.hakurekisteri.opiskelija

import fi.vm.sade.hakurekisteri.storage.repository.JDBCJournal
import scala.slick.lifted.ColumnOrdered
import fi.vm.sade.hakurekisteri.storage.Identified
import org.joda.time.DateTime
import scala.slick.driver.JdbcDriver
import scala.slick.driver.JdbcDriver.simple._
import java.util.UUID
import scala.slick.jdbc.meta.MTable

class OpiskelijaJournal(database: Database) extends JDBCJournal[Opiskelija, OpiskelijaTable, ColumnOrdered[Long]] {
  override def toResource(row: OpiskelijaTable#TableElementType): Opiskelija with Identified = Opiskelija(row._2,row._3,row._4,row._5,new DateTime(row._6), row._7.map(new DateTime(_))).identify(row._1)
  override def toRow(o: Opiskelija with Identified): OpiskelijaTable#TableElementType = (o.id, o.oppilaitosOid, o.luokkataso, o.luokka, o.henkiloOid, o.alkuPaiva.getMillis, o.loppuPaiva.map(_.getMillis), System.currentTimeMillis())

  val opiskelijat = TableQuery[OpiskelijaTable]
    database withSession(
      implicit session =>
        if (MTable.getTables("opiskelija").list().isEmpty) {
          opiskelijat.ddl.create
        }
    )

  override val table = opiskelijat
  override val db: JdbcDriver.simple.Database = database
  override val journalSort = (o: OpiskelijaTable) => o.inserted.asc
}

class OpiskelijaTable(tag: Tag) extends Table[(UUID, String, String, String, String, Long, Option[Long], Long)](tag, "opiskelija") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def resourceId = column[UUID]("resource_id", O.DBType("VARCHAR(36)"))
  def oppilaitosOid = column[String]("oppilaitos_oid")
  def luokkataso = column[String]("luokkataso")
  def luokka = column[String]("luokka")
  def henkiloOid = column[String]("henkilo_oid")
  def alkuPaiva = column[Long]("alku_paiva")
  def loppuPaiva = column[Option[Long]]("loppu_paiva")
  def inserted = column[Long]("inserted")
  // Every table needs a * projection with the same type as the table's type parameter
  def * = (resourceId, oppilaitosOid, luokkataso, luokka, henkiloOid, alkuPaiva, loppuPaiva, inserted)
}
