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

class OpiskelijaJournal(database: Database) extends Journal[Opiskelija,  UUID] {

  val opiskelijat = TableQuery[OpiskelijaTable]
    database withSession(
      implicit session =>
        if (MTable.getTables("opiskelija").list().isEmpty) {
          opiskelijat.ddl.create
        }
    )


  override def addModification(delta:Delta[Opiskelija, UUID]) {
    database withSession {
      implicit session =>
        opiskelijat += delta
    }
  }


  def loadFromDb(latestQuery:Option[Long]): List[OpiskelijaTable#TableElementType] = latestQuery match  {
    case None =>
      database withSession {
        implicit session =>
          latestResources.list

      }
    case Some(latest) =>

      database withSession {
        implicit session =>
          opiskelijat.filter(_.inserted >= latest).sortBy(_.inserted.asc).list
      }


  }

  override def journal(latest:Option[Long]): Seq[Delta[Opiskelija, UUID]] = loadFromDb(latest)

  def latestResources = {
    val latest = for {
      (id, resource) <- opiskelijat.groupBy(_.resourceId)
    } yield (id, resource.map(_.inserted).max)

    val result = for {
      delta <- opiskelijat
      (id, timestamp) <- latest
      if delta.resourceId === id && delta.inserted === timestamp.getOrElse(0)

    } yield delta

    result.sortBy(_.inserted.asc)
  }

}

class OpiskelijaTable(tag: Tag) extends Table[Delta[Opiskelija, UUID]](tag, "opiskelija") {
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
  def * =
    (resourceId, oppilaitosOid, luokkataso, luokka, henkiloOid, alkuPaiva, loppuPaiva, source,  inserted, deleted) <>
      ((OpiskelijaTable.apply _ ).tupled , OpiskelijaTable.unapply)
}

case class Foo(bar:String, bax: Int)



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
