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
  override def delta(row: SuoritusTable#TableElementType): Delta[Suoritus, UUID] = row match {
    case (resourceId, komo, myontaja, tila, valmistuminen, henkiloOid, yksilollistaminen, suoritusKieli, kuvaus, vuosi, tyyppi, source, inserted, true) => Deleted(UUID.fromString(resourceId), source)
    case (resourceId, Some(komo), myontaja, Some(tila), Some(valmistuminen), henkiloOid, Some(yks), Some(suoritusKieli), _, _, _, source, inserted, false) =>
      Updated(VirallinenSuoritus(komo, myontaja, tila, LocalDate.parse(valmistuminen), henkiloOid, yksilollistaminen.withName(yks), suoritusKieli, lahde = source).identify(UUID.fromString(resourceId)))
    case (resourceId, _, myontaja, _, _, henkiloOid, _, _, Some(kuvaus), Some(vuosi), Some(tyyppi), source, inserted, false) =>
      Updated(VapaamuotoinenSuoritus(henkiloOid,kuvaus, myontaja, vuosi, tyyppi, lahde = source).identify(UUID.fromString(resourceId)))
  }

  override def update(suoritus: Suoritus with Identified[UUID]): SuoritusTable#TableElementType = suoritus match {
    case o: VirallinenSuoritus =>
      (o.id.toString, Some(o.komo), o.myontaja, Some(o.tila), Some(o.valmistuminen.toString), o.henkiloOid, Some(o.yksilollistaminen.toString), Some(o.suoritusKieli), None, None, None, o.source, Platform.currentTime, false)
    case s: VapaamuotoinenSuoritus =>
      (s.id.toString, None,s.myontaja, None, None, s.henkiloOid, None, None, Some(s.kuvaus), Some(s.vuosi), Some(s.tyyppi), s.source, Platform.currentTime, false)


  }
  override def delete(id:UUID, source: String) = currentState(id) match {
    case (resourceId, komo, myontaja, tila, valmistuminen, henkiloOid, yksilollistaminen, suoritusKieli, kuvaus, vuosi, tyyppi, _, _, _) =>
      (resourceId, komo, myontaja, tila, valmistuminen, henkiloOid, yksilollistaminen, suoritusKieli, kuvaus, vuosi, tyyppi, source, Platform.currentTime, true)
  }

  val suoritukset = TableQuery[SuoritusTable]
  database withSession(implicit session =>
    if (MTable.getTables("suoritus").list().isEmpty) {
      suoritukset.ddl.create
    }
  )

  override def newest: (SuoritusTable) => ColumnOrdered[Long] = _.inserted.desc

  override def filterByResourceId(id: UUID): (SuoritusTable) => Column[Boolean] = _.resourceId === id.toString

  override val table = suoritukset
  override val db: JdbcDriver.simple.Database = database
  override val sortColumn = (s: SuoritusTable) => s.inserted

  override def timestamp(resource: SuoritusTable): lifted.Column[Long] = resource.inserted

  override def timestamp(resource: SuoritusTable#TableElementType): Long = resource._13

  override val idColumn: (SuoritusTable) => JdbcDriver.simple.Column[String] = _.resourceId

  override def latestResources  = {
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

class SuoritusTable(tag: Tag) extends Table[(String, Option[String], String, Option[String], Option[String], String, Option[String], Option[String], Option[String], Option[Int], Option[String], String, Long, Boolean)](tag, "suoritus") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def resourceId = column[String]("resource_id")
  def myontaja = column[String]("myontaja")
  def henkiloOid = column[String]("henkilo_oid")
  def source = column[String]("source")
  def inserted = column[Long]("inserted")
  def deleted = column[Boolean]("deleted")

  //virallinen
  def komo = column[Option[String]]("komo")
  def tila = column[Option[String]]("tila")
  def valmistuminen = column[Option[String]]("valmistuminen")
  def yksilollistaminen = column[Option[String]]("yksilollistaminen")
  def suoritusKieli = column[Option[String]]("suoritus_kieli")

  //vapaamuotoinen
  def kuvaus = column[Option[String]]("kuvaus")
  def vuosi = column[Option[Int]]("vuosi")
  def tyyppi = column[Option[String]]("tyyppi")

  // Every table needs a * projection with the same type as the table's type parameter
  def *  = (resourceId, komo, myontaja, tila, valmistuminen, henkiloOid, yksilollistaminen, suoritusKieli, kuvaus, vuosi, tyyppi, source, inserted, deleted)
}

