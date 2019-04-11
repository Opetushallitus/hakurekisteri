package fi.vm.sade.hakurekisteri.batchimport

import java.util.UUID

import akka.actor.{Actor, ActorLogging}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import fi.vm.sade.hakurekisteri.rest.support.JDBCUtil

import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.util.{Failure, Success, Try}

case class QueryImportBatchReferences(organisations: Set[String])
case class ReferenceResult(references: Seq[UUID])

class ImportBatchOrgActor(db: Database) extends Actor with ActorLogging {
  implicit val executionContext: ExecutionContext = context.dispatcher
  val table = TableQuery[ImportBatchOrgTable]
  if (db == null) {
    log.warning("Got null db object! This should not happen in production.")
  } else {
    JDBCUtil.createSchemaForTable(table, db)
  }

  def insertIfNotExists(resourceId: UUID, oid: String, created: Long) = table.map(u => (u.resourceId, u.oid, u.inserted)).forceInsertQuery {

    val exists = table.filter(r => (r.resourceId === resourceId.bind && r.oid === oid.bind)).exists
    Query((resourceId.bind, oid.bind, created.bind)).filter(_ => !exists)
  }

  override def receive: Receive = {
    case ImportBatchOrgs(resourceId, orgs) =>
      if(orgs.isEmpty) {
        log.info(s"Batch $resourceId had no organizations!")
      } else {
        val rows = orgs.map(org => toRow(ImportBatchOrg(resourceId, org)))
        val toBeInserted = rows.map { row => table.forceInsert(row) }
        val deleteAndInsert = DBIO.sequence(Seq(table.filter(_.resourceId === resourceId.bind).delete) ++ toBeInserted)
        Try(run(deleteAndInsert)) match {
          case Failure(f) =>
            log.error(s"Couldn't insert $resourceId organizations ($orgs)!")
          case _ =>
            log.debug(s"$resourceId organizations ($orgs) inserted!")
        }
      }

    case i: ImportBatchOrg =>
      log.info(s"Saving import batch organisation ${i.oid}!")
      val (resourceId, oid, created)= toRow(i)
      Try(run(insertIfNotExists(resourceId, oid, created)))

    case QueryImportBatchReferences(orgs) =>
      //val query = sql"select resource_id,oid from import_batch_org where resource_id in (select resource_id from import_batch_org where oid in ($o))".as[(String,String)]
      val subQuery = table.filter(_.oid.inSet(orgs)).map(_.resourceId)
      val query = table.filter(_.resourceId.in(subQuery)).result
      Try(Await.result(db.run(query).map(result => {
        val byUUID: Map[UUID, Set[String]] = result.groupBy(_._1).mapValues(_.map(_._2).toSet)
        byUUID.filter(_._2.subsetOf(orgs)).keys.toSeq
      }).map(ReferenceResult(_)), 10.seconds)) match {
        case Success(result) =>
          sender ! result
        case Failure(exception) =>
          log.error(exception, s"Could not fetch import_batch_org's for ${orgs}!")
          sender ! exception
      }
    case _ =>
  }

  private def toRow(i: ImportBatchOrg) = (i.resourceId, i.oid, Platform.currentTime)
  private def run[R](r: DBIO[R]) = Await.result(db.run(r), 10.seconds)
}
