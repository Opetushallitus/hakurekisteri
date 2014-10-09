package fi.vm.sade.hakurekisteri.rest.support

import scala.slick.lifted._
import fi.vm.sade.hakurekisteri.storage.repository.{Delta, Journal}
import scala.slick.lifted
import scala.compat.Platform
import fi.vm.sade.hakurekisteri.storage.Identified
import scala.language.existentials
import org.slf4j.LoggerFactory
import scala.slick.jdbc.meta.MTable

import scala.slick.driver.JdbcDriver
import java.util.UUID
import scala.slick.jdbc.{PositionedResult, PositionedParameters}
import scala.slick.ast.{BaseTypedType, TypedType}
import scala.Some
import fi.vm.sade.hakurekisteri.storage.repository.Deleted
import fi.vm.sade.hakurekisteri.storage.repository.Updated
import scala.slick.lifted.TableQuery
import scala.slick.lifted.ShapedValue


object HakurekisteriDriver extends JdbcDriver {

  override val columnTypes = new super.JdbcTypes{

    override val uuidJdbcType: super.UUIDJdbcType = new UUIDJdbcType

    class UUIDJdbcType extends super.UUIDJdbcType {
      override def sqlType = java.sql.Types.VARCHAR
      override def setValue(v: UUID, p: PositionedParameters) = p.setString(v.toString)
      override def setOption(v: Option[UUID], p: PositionedParameters) = p.setStringOption(v.map(_.toString))
      override def nextValue(r: PositionedResult) = UUID.fromString(r.nextString)
      override def updateValue(v: UUID, r: PositionedResult) = r.updateString(v.toString)
      override def valueToSQLLiteral(value: UUID) = if(value eq null) "NULL" else {
        val serialized = value.toString
        val sb = new StringBuilder
        sb append '\''
        for(c <- serialized) c match {
          case '\'' => sb append "''"
          case _ => sb append c
        }
        sb append '\''
        sb.toString
      }
    }
  }

}

import HakurekisteriDriver.simple._

abstract class JournalTable[R <: Resource[I], I, ResourceRow](tag: Tag, name: String)(implicit val idType: TypedType[I]) extends Table[Delta[R,I]](tag, name) with HakurekisteriColumns {

  def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
  def resourceId = column[I]("resource_id")

  def source = column[String]("source")
  def inserted = column[Long]("inserted")
  def deleted = column[Boolean]("deleted")
  val journalEntryShape = (resourceId, inserted, deleted).shaped
  type ShapedJournalRow = (lifted.Column[I], lifted.Column[String], lifted.Column[Long], lifted.Column[Boolean])
  type JournalRow = (I, Long, Boolean)


  val resource: ResourceRow => R

  val extractSource: ResourceRow => String

  def delta(resourceId: I, inserted: Long, deleted: Boolean)(resourceData:ResourceRow):Delta[R, I] =
    if (deleted){
      Deleted(resourceId, extractSource(resourceData))
    }

    else
    {
      val resource1 = resource(resourceData)
      Updated(resource1.identify(resourceId))
    }


  def deltaShaper(j: (I, Long, Boolean), rd: ResourceRow): Delta[R, I] = (delta _).tupled(j)(rd)

  val deletedValues: (String) =>  ResourceRow

  def rowShaper(d: Delta[R, I]) = d match {
    case Deleted(id, source) => Some((id, Platform.currentTime, true), deletedValues(source))
    case Updated(r: R with Identified[I]) => row(r).map(updateRow(r))

  }


  def updateRow(r: R with Identified[I])(resourceData: ResourceRow) = ((r.id, Platform.currentTime, false), resourceData)

  def row(resource: R): Option[ResourceRow]
  def resourceShape: ShapedValue[_, ResourceRow]


  val combinedShape = journalEntryShape zip resourceShape


  def * : ProvenShape[Delta[R, I]] = {
    combinedShape <> ((deltaShaper _).tupled, rowShaper)
  }


}


class JDBCJournal[R <: Resource[I], I, T <: JournalTable[R,I, _]](val table: TableQuery[T])(implicit val db: Database, val idType: BaseTypedType[I]) extends Journal[R,  I] {



  val log = LoggerFactory.getLogger(getClass)

  lazy val tableName = table.baseTableRow.tableName

  db withSession(
    implicit session =>
      if (MTable.getTables(tableName).list().isEmpty) {
        table.ddl.create
      }
    )


  log.debug(s"started ${getClass.getSimpleName} with table $tableName")

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
      if columnExtensionMethods(delta.resourceId) === id &&  columnExtensionMethods(delta.inserted).===(optionColumnExtensionMethods(timestamp).getOrElse(0))

    } yield delta

    result.sortBy(_.inserted.asc)
  }


}


