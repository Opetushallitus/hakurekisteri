package fi.vm.sade.hakurekisteri.henkilo

import fi.vm.sade.hakurekisteri.storage.repository.JDBCJournal
import scala.slick.lifted.ColumnOrdered
import fi.vm.sade.hakurekisteri.storage.Identified
import org.joda.time.DateTime
import scala.slick.driver.JdbcDriver
import scala.slick.driver.JdbcDriver.simple._
import java.util.UUID
import scala.slick.jdbc.meta.MTable
import org.json4s._
import org.json4s.jackson.Serialization.{read, write}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport


class HenkiloJournal(database: Database) extends JDBCJournal[Henkilo, HenkiloTable, ColumnOrdered[Long]] with HakurekisteriJsonSupport {
  override def toResource(row: HenkiloTable#TableElementType): Henkilo with Identified = read[Henkilo](row._2).identify(UUID.fromString(row._1))
  override def update(o: Henkilo with Identified): HenkiloTable#TableElementType = (o.id.toString, write(o), System.currentTimeMillis())
  override def delete(id:UUID) = ???
  val henkilot = TableQuery[HenkiloTable]
    database withSession(
      implicit session =>
        if (MTable.getTables("henkilo").list().isEmpty) {
          println("creating henkilo table")
          henkilot.ddl.create
        }
    )

  override val table = henkilot
  override val db: JdbcDriver.simple.Database = database
  override val journalSort = (o: HenkiloTable) => o.inserted.asc
}

class HenkiloTable(tag: Tag) extends Table[(String, String, Long)](tag, "henkilo") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def resourceId = column[String]("resource_id")
  def henkilo = column[String]("henkilo", O.DBType("CLOB"))
  def inserted = column[Long]("inserted")
  def * = (resourceId, henkilo, inserted)
}
