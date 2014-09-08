package fi.vm.sade.hakurekisteri.arvosana

import scala.slick.driver.JdbcDriver.simple._
import fi.vm.sade.hakurekisteri.storage.repository.{Deleted, Updated, Delta, JDBCJournal}
import scala.slick.lifted.ColumnOrdered
import fi.vm.sade.hakurekisteri.storage.Identified
import scala.slick.jdbc.meta.MTable
import scala.slick.driver.JdbcDriver
import java.util.UUID
import scala.compat.Platform
import org.slf4j.LoggerFactory
import scala.slick.lifted
import org.joda.time.LocalDate

class ArvosanaJournal(database: Database) extends JDBCJournal[Arvosana, ArvosanaTable, ColumnOrdered[Long], UUID] {

  val logger = LoggerFactory.getLogger(getClass)

  override def delta(row: ArvosanaTable#TableElementType): Delta[Arvosana, UUID] =
    row match {
      case (resourceId, _, _, _, _, _, _, _,_, source,  _, true) => Deleted(UUID.fromString(resourceId), source)
      case (id,suoritus, arvosana, asteikko, aine, lisatieto, valinnainen, pisteet, myonnetty, source,  _, _) =>
        Updated(Arvosana(UUID.fromString(suoritus), Arvio(arvosana, asteikko, pisteet), aine, lisatieto, valinnainen, myonnetty = myonnetty.map(LocalDate.parse), source).identify(UUID.fromString(id)))
    }

  override def delete(id:UUID, source: String) =  currentState(id) match
  { case (foundid,suoritus, arvosana, asteikko, aine, lisatieto, valinnainen, pisteet, myonnetty, _ ,  _, _)  =>
      (foundid,suoritus, arvosana, asteikko, aine, lisatieto, valinnainen, pisteet,myonnetty, source, Platform.currentTime,true)}

  override def update(o:Arvosana with Identified[UUID]) = o.arvio match {
    case Arvio410(arvosana) =>
      logger.debug("toRow lisatieto {}", o.lisatieto)
      (o.id.toString, o.suoritus.toString, arvosana, Arvio.ASTEIKKO_4_10 , o.aine, o.lisatieto, o.valinnainen, None, o.myonnetty.map(_.toString), o.source, Platform.currentTime, false)
    case ArvioYo(arvosana, pisteet) =>
      (o.id.toString, o.suoritus.toString, arvosana, Arvio.ASTEIKKOYO , o.aine, o.lisatieto, o.valinnainen, pisteet, o.myonnetty.map(_.toString), o.source, Platform.currentTime, false)
    case a:Arvio if a == Arvio.NA => throw UnknownAssessmentResultException
  }


  object UnknownAssessmentResultException extends IllegalArgumentException("Trying to save unknown assessment result")

  val arvosanat = TableQuery[ArvosanaTable]
  database withSession(
    implicit session =>
      if (MTable.getTables("arvosana").list().isEmpty) {
        arvosanat.ddl.create
      }
    )



  override def newest: (ArvosanaTable) => ColumnOrdered[Long] = _.inserted.desc

  override def filterByResourceId(id: UUID): (ArvosanaTable) => Column[Boolean] = _.resourceId === id.toString


  override val table = arvosanat
  override val db: JdbcDriver.simple.Database = database
  override val sortColumn = (a: ArvosanaTable) => a.inserted

  override def timestamp(resource: ArvosanaTable): Column[Long] =  resource.inserted

  override def timestamp(resource: ArvosanaTable#TableElementType): Long = resource._11

  override val idColumn: (ArvosanaTable) => Column[String] = _.resourceId


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

class ArvosanaTable(tag: Tag) extends Table[(String, String, String, String, String, Option[String], Boolean, Option[Int], Option[String], String, Long, Boolean)](tag, "arvosana") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def resourceId = column[String]("resource_id")
  def suoritus = column[String]("suoritus")
  def arvosana = column[String]("arvosana")
  def asteikko = column[String]("asteikko")
  def aine = column[String]("aine")
  def lisatieto = column[Option[String]]("lisatieto")
  def valinnainen = column[Boolean]("valinnainen")
  def pisteet = column[Option[Int]]("pisteet")
  def myonnetty = column[Option[String]]("myonnetty")
  def inserted = column[Long]("inserted")
  def source = column[String]("source")
  def deleted = column[Boolean]("deleted")
  // Every table needs a * projection with the same type as the table's type parameter
  def * = (resourceId, suoritus, arvosana, asteikko, aine, lisatieto, valinnainen, pisteet, myonnetty, source,  inserted, deleted)
}