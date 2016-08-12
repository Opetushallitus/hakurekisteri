package fi.vm.sade.hakurekisteri.rest.support

import akka.actor.ActorSystem
import akka.event.Logging
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import fi.vm.sade.hakurekisteri.storage.repository._
import slick.ast.BaseTypedType
import slick.jdbc.meta.MTable
import slick.lifted

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

class JDBCJournal[R <: Resource[I, R], I, T <: JournalTable[R, I, _]](val table: lifted.TableQuery[T])
                                                                     (implicit val db: Database, val idType: BaseTypedType[I], implicit val system: ActorSystem)
  extends Journal[R, I] {

  implicit val ec: ExecutionContext = system.dispatcher
  val log = Logging.getLogger(system, this)
  lazy val tableName = table.baseTableRow.tableName
  val queryTimeout: Duration = 1.minute

  Await.result(db.run(MTable.getTables(tableName).flatMap((t: Vector[MTable]) => {
    if (t.isEmpty) {
      schemaActionExtensionMethods(tableQueryToTableQueryExtensionMethods(table).schema).create
    } else {
      DBIO.successful(())
    }
  })), queryTimeout)


  log.info(s"started ${getClass.getSimpleName} with table $tableName")

  override def addModification(o: Delta[R, I]): Unit = {
    Await.result(db.run(table += o), queryTimeout)
  }

  override def journal(latestQuery: Option[Long]): Seq[Delta[R, I]] = latestQuery match {
    case None => Await.result(db.run(latestResources.result), 60.minutes)
    case Some(lat) => Await.result(db.run(latestResources.filter(_.inserted >= lat).result), queryTimeout)
  }

  val resourcesWithVersions = table.groupBy[lifted.Rep[I], I, lifted.Rep[I], T](_.resourceId)

  val latest = for {
    (id: lifted.Rep[I], resource: lifted.Query[T, T#TableElementType, Seq]) <- resourcesWithVersions
  } yield (id, resource.map(_.inserted).max)

  val result: lifted.Query[T, Delta[R, I], Seq] = for (
    delta <- table;
    (id, timestamp) <- latest
    if delta.resourceId === id && delta.inserted === timestamp

  ) yield delta

  val latestResources = {
    result.sortBy(_.inserted.asc)
  }
}
