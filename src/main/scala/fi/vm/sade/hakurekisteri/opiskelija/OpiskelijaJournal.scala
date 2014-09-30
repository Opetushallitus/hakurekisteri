package fi.vm.sade.hakurekisteri.opiskelija

import fi.vm.sade.hakurekisteri.storage.repository._
import org.joda.time.DateTime
import scala.slick.driver.JdbcDriver.simple._
import java.util.UUID
import scala.slick.jdbc.meta.MTable
import scala.compat.Platform
import scala.Some
import fi.vm.sade.hakurekisteri.storage.repository.Deleted
import fi.vm.sade.hakurekisteri.storage.repository.Updated
import scala.slick.driver.JdbcDriver

abstract class JournalTable[R, I](tag: Tag, name: String) extends Table[Delta[R,I]](tag, name) {

  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def resourceId = column[String]("resource_id")

  def source = column[String]("source")
  def inserted = column[Long]("inserted")
  def deleted = column[Boolean]("deleted")



}

trait NewJDBCJournal[R, I, T <: JournalTable[R, I]] extends Journal[R,  I] {

  val table: TableQuery[T]

  val db: Database

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

class OpiskelijaJournal(database: Database) extends NewJDBCJournal[Opiskelija,  UUID, OpiskelijaTable] {

  val table = TableQuery[OpiskelijaTable]
  database withSession(
    implicit session =>
      if (MTable.getTables("opiskelija").list().isEmpty) {
        table.ddl.create
      }
  )
  override val db: JdbcDriver.simple.Database = database
}

class OpiskelijaTable(tag: Tag) extends JournalTable[Opiskelija, UUID](tag, "opiskelija") {
  def oppilaitosOid = column[String]("oppilaitos_oid")
  def luokkataso = column[String]("luokkataso")
  def luokka = column[String]("luokka")
  def henkiloOid = column[String]("henkilo_oid")
  def alkuPaiva = column[Long]("alku_paiva")
  def loppuPaiva = column[Option[Long]]("loppu_paiva")

  def * =
    (resourceId, oppilaitosOid, luokkataso, luokka, henkiloOid, alkuPaiva, loppuPaiva, source,  inserted, deleted) <>
      ((OpiskelijaTable.apply _ ).tupled , OpiskelijaTable.unapply)
}


object OpiskelijaTable {


   def apply(resourceId: String, oppilaitosOid: String, luokkataso: String, luokka: String, henkiloOid: String, alkuPaiva: Long, loppuPaiva: Option[Long], source: String, inserted: Long, deleted: Boolean): Delta[Opiskelija, UUID] =
     if (deleted)
       Deleted(UUID.fromString(resourceId), source)
    else
       Updated(Opiskelija(oppilaitosOid, luokkataso, luokka, henkiloOid,new DateTime(alkuPaiva), loppuPaiva.map(new DateTime(_)), source).identify(UUID.fromString(resourceId)))


  def unapply(d:Delta[Opiskelija, UUID]): Option[(String, String, String, String, String, Long, Option[Long], String, Long, Boolean)] = d match {
    case Deleted(id, source) => Some((id.toString, "", "", "", "", 0L, None, source, Platform.currentTime, true))
    case Updated(o) => Some(o.id.toString, o.oppilaitosOid, o.luokkataso, o.luokka, o.henkiloOid, o.alkuPaiva.getMillis, o.loppuPaiva.map(_.getMillis), o.source,  Platform.currentTime, false)
  }




}
