package fi.vm.sade.hakurekisteri.opiskelija

import fi.vm.sade.hakurekisteri.storage.repository._
import org.joda.time.DateTime
import scala.slick.driver.JdbcDriver.simple._
import java.util.UUID
import scala.slick.jdbc.meta.MTable
import scala.compat.Platform
import scala.slick.lifted.{ProvenShape, TableQuery}
import scala.slick.lifted
import scala.Some
import fi.vm.sade.hakurekisteri.storage.repository.Deleted
import fi.vm.sade.hakurekisteri.storage.repository.Updated
import scala.slick.lifted.ShapedValue
import fi.vm.sade.hakurekisteri.rest.support.Resource
import fi.vm.sade.hakurekisteri.storage.Identified
import scala.slick.ast.Node

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
    case Updated(r: R with Identified[I]) => Some((r.id.toString, r.asInstanceOf[R].source, Platform.currentTime, false), row(r))

  }

  def row(resource: R): ResourceRow
  def resourceShape: ShapedValue[_, ResourceRow]


  val combinedShape = journalEntryShape zip resourceShape


  def * : ProvenShape[Delta[R, I]] = {
    combinedShape <> ((deltaShaper _).tupled, rowShaper)
  }


}



abstract class NewJDBCJournal[R <: Resource[I], I, T <: JournalTable[R,I, _]](val table: TableQuery[T]) extends Journal[R,  I] {



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

class OpiskelijaJournal(override val db: Database) extends NewJDBCJournal[Opiskelija, UUID, OpiskelijaTable](TableQuery[OpiskelijaTable]) {
  db withSession(
    implicit session =>
      if (MTable.getTables("opiskelija").list().isEmpty) {
        table.ddl.create
      }
  )

}



class OpiskelijaTable(tag: Tag) extends JournalTable[Opiskelija, UUID, (String, String, String, String, Long, Option[Long], String)](tag, "opiskelija") {
  def oppilaitosOid = column[String]("oppilaitos_oid")
  def luokkataso = column[String]("luokkataso")
  def luokka = column[String]("luokka")
  def henkiloOid = column[String]("henkilo_oid")
  def alkuPaiva = column[Long]("alku_paiva")
  def loppuPaiva = column[Option[Long]]("loppu_paiva")

  val deletedValues = ("", "", "", "", 0L, None, "")

  override def resourceShape = (oppilaitosOid, luokkataso, luokka, henkiloOid, alkuPaiva, loppuPaiva, source).shaped


  override def row(o: Opiskelija): (String, String, String, String, Long, Option[Long], String) = (o.oppilaitosOid, o.luokkataso, o.luokka, o.henkiloOid, o.alkuPaiva.getMillis, o.loppuPaiva.map(_.getMillis), o.source)

  override def getId(serialized: String): UUID = UUID.fromString(serialized)

  def opiskelija(oppilaitosOid: String, luokkataso:String, luokka:String, henkiloOid:String, alkuPaiva: Long, loppuPaiva: Option[Long], source: String): Opiskelija =
  Opiskelija(oppilaitosOid: String, luokkataso: String, luokka: String, henkiloOid: String, new DateTime(alkuPaiva), loppuPaiva.map(new DateTime(_)),source)

  override val resource = (opiskelija _).tupled

}

