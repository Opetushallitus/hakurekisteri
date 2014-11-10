package fi.vm.sade.hakurekisteri.rest.support

import java.util.UUID

import akka.actor.ActorSystem
import akka.event.Logging
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.storage.repository.{Deleted, Delta, Journal, Updated}
import org.json4s.JsonAST.JValue

import scala.compat.Platform
import scala.language.existentials
import scala.slick.ast.{BaseTypedType, TypedType}
import scala.slick.driver.JdbcDriver
import scala.slick.jdbc.meta.MTable
import scala.slick.jdbc.{JdbcType, PositionedParameters, PositionedResult}
import scala.slick.lifted
import scala.slick.lifted._
import java.sql.{ResultSet, PreparedStatement}
import scala.reflect.ClassTag
import scala.Some
import fi.vm.sade.hakurekisteri.storage.repository.Deleted
import fi.vm.sade.hakurekisteri.storage.repository.Updated
import scala.Some
import fi.vm.sade.hakurekisteri.storage.repository.Deleted
import fi.vm.sade.hakurekisteri.storage.repository.Updated
import scala.slick.lifted.TableQuery
import scala.xml.{XML, Elem}


object HakurekisteriDriver extends JdbcDriver {

  override val columnTypes = new super.JdbcTypes {

    override val uuidJdbcType: super.UUIDJdbcType = new UUIDJdbcType

    class UUIDJdbcType(implicit override val classTag: ClassTag[UUID]) extends super.UUIDJdbcType {
      override def sqlType = java.sql.Types.VARCHAR
      override def setValue(v: UUID, p: PreparedStatement, idx: Int) = p.setString(idx, v.toString)
      override def getValue(r: ResultSet, idx: Int) = UUID.fromString(r.getString(idx))
      override def updateValue(v: UUID, r: ResultSet, idx: Int) = r.updateString(idx, v.toString)
      override def valueToSQLLiteral(value: UUID) = if (value eq null) {
        "NULL"
      } else {
        val serialized = value.toString
        val sb = new StringBuilder
        sb append '\''
        for (c <- serialized) c match {
          case '\'' => sb append "''"
          case _ => sb append c
        }
        sb append '\''
        sb.toString()
      }
    }
  }

  override val simple = new SimpleQL with Implicits

  trait Implicits extends super.Implicits {
    class JValueType(implicit tmd: JdbcType[String], override val classTag: ClassTag[JValue]) extends HakurekisteriDriver.MappedJdbcType[JValue, String] with BaseTypedType[JValue] {
      import org.json4s.jackson.JsonMethods._
      override def newSqlType: Option[Int] = Option(java.sql.Types.CLOB)
      override def sqlTypeName: String = "TEXT"
      override def comap(json: String): JValue = parse(json)
      override def map(data: JValue): String = compact(render(data))
    }

    implicit val jvalueType = new JValueType

    class ElemType(implicit tmd: JdbcType[String], override val classTag: ClassTag[Elem]) extends HakurekisteriDriver.MappedJdbcType[Elem, String] with BaseTypedType[Elem] {
      override def newSqlType: Option[Int] = Option(java.sql.Types.CLOB)
      override def sqlTypeName: String = "TEXT"
      override def comap(xml: String): Elem = XML.loadString(xml)
      override def map(data: Elem): String = data.toString()
    }

    implicit val elemType = new ElemType
  }
}

import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.simple._

abstract class JournalTable[R <: Resource[I, R], I, ResourceRow](tag: Tag, name: String)(implicit val idType: TypedType[I]) extends Table[Delta[R, I]](tag, name) with HakurekisteriColumns {
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

  def delta(resourceId: I, inserted: Long, deleted: Boolean)(resourceData: ResourceRow): Delta[R, I] = if (deleted) {
    Deleted(resourceId, extractSource(resourceData))
  } else {
    val resource1 = resource(resourceData)
    Updated(resource1.identify(resourceId))
  }

  def deltaShaper(j: (I, Long, Boolean), rd: ResourceRow): Delta[R, I] = (delta _).tupled(j)(rd)

  val deletedValues: (String) => ResourceRow

  def rowShaper(d: Delta[R, I]) = d match {
    case Deleted(id, source) => Some((id, Platform.currentTime, true), deletedValues(source))
    case Updated(r) =>
      row(r).map(updateRow(r))
  }

  def updateRow(r: R with Identified[I])(resourceData: ResourceRow) = ((r.id, Platform.currentTime, false), resourceData)

  def row(resource: R): Option[ResourceRow]

  def resourceShape: ShapedValue[_, ResourceRow]

  val combinedShape = journalEntryShape zip resourceShape

  def * : ProvenShape[Delta[R, I]] = {
    combinedShape <>((deltaShaper _).tupled, rowShaper)
  }
}

class JDBCJournal[R <: Resource[I, R], I, T <: JournalTable[R, I, _]](val table: TableQuery[T])(implicit val db: Database, val idType: BaseTypedType[I], implicit val system: ActorSystem) extends Journal[R, I] {
  val log = Logging.getLogger(system, this)
  lazy val tableName = table.baseTableRow.tableName

  db withSession (
    implicit session =>
      if (MTable.getTables(tableName).list.isEmpty) {
        table.ddl.create
      }
    )

  log.debug(s"started ${getClass.getSimpleName} with table $tableName")

  override def addModification(o: Delta[R, I]): Unit = db withSession (
    implicit session =>
      table += o
    )

  override def journal(latestQuery: Option[Long]): Seq[Delta[R, I]] = latestQuery match {
    case None =>
      db withSession {
        implicit session =>
          queryToAppliedQueryInvoker(latestResources).list

      }
    case Some(lat) =>
      db withSession {
        implicit session =>
          table.filter(_.inserted >= lat).sortBy(_.inserted.asc).list
      }
  }

  val resourcesWithVersions = table.groupBy[lifted.Column[I], I, lifted.Column[I], T](_.resourceId)

  val latest = for {
    (id: lifted.Column[I], resource: lifted.Query[T, T#TableElementType, Seq]) <- resourcesWithVersions
  } yield (id, resource.map(_.inserted).max)

  val result: lifted.Query[T, Delta[R, I], Seq] = for (
    delta <- table;
    (id, timestamp) <- latest
    if columnExtensionMethods(delta.resourceId) === id && columnExtensionMethods(delta.inserted).===(optionColumnExtensionMethods(timestamp).getOrElse(0))

  ) yield delta

  val latestResources = {
    result.sortBy(_.inserted.asc)
  }
}


