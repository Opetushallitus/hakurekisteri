package fi.vm.sade.hakurekisteri.arvosana

import scala.slick.driver.JdbcDriver.simple._
import fi.vm.sade.hakurekisteri.storage.repository.JDBCJournal
import scala.slick.lifted.ColumnOrdered
import fi.vm.sade.hakurekisteri.storage.Identified
import scala.slick.jdbc.meta.MTable
import scala.slick.driver.JdbcDriver
import java.util.UUID
import scala.compat.Platform
import org.slf4j.LoggerFactory

class ArvosanaJournal(database: Database) extends JDBCJournal[Arvosana, ArvosanaTable, ColumnOrdered[Long]] {

  val logger = LoggerFactory.getLogger(getClass)

  override def toResource(row: ArvosanaTable#TableElementType): Arvosana with Identified = row match {
    case (id,suoritus, arvosana, asteikko, aine, lisatieto, valinnainen, inserted) =>
      logger.debug("toResource lisatieto {}", lisatieto)
      Arvosana(UUID.fromString(suoritus), Arvio(arvosana, asteikko), aine, lisatieto, valinnainen).identify(UUID.fromString(id))
  }

  override def toRow(o: Arvosana with Identified): ArvosanaTable#TableElementType = o.arvio match {
    case Arvio410(arvosana) =>
      logger.debug("toRow lisatieto {}", o.lisatieto)
      (o.id.toString, o.suoritus.toString, arvosana, Arvio.ASTEIKKO_4_10 , o.aine, o.lisatieto, o.valinnainen, Platform.currentTime)
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

  override val table = arvosanat
  override val db: JdbcDriver.simple.Database = database
  override val journalSort = (o: ArvosanaTable) => o.inserted.asc
}

class ArvosanaTable(tag: Tag) extends Table[(String, String, String, String, String, Option[String], Boolean, Long)](tag, "arvosana") {
  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def resourceId = column[String]("resource_id")
  def suoritus = column[String]("suoritus")
  def arvosana = column[String]("arvosana")
  def asteikko = column[String]("asteikko")
  def aine = column[String]("aine")
  def lisatieto = column[Option[String]]("lisatieto")
  def valinnainen = column[Boolean]("valinnainen")
  def inserted = column[Long]("inserted")
  // Every table needs a * projection with the same type as the table's type parameter
  def * = (resourceId, suoritus, arvosana, asteikko, aine, lisatieto, valinnainen, inserted)
}