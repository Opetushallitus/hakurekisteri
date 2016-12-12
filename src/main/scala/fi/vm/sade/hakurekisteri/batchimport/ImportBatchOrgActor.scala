package fi.vm.sade.hakurekisteri.batchimport

import java.util.UUID

import akka.actor.Actor.Receive
import akka.actor.{ActorRef, ActorLogging, Actor}
import fi.vm.sade.hakurekisteri.rest.support.JDBCUtil
import fi.vm.sade.hakurekisteri.storage.ResourceService
import fi.vm.sade.hakurekisteri.storage.repository.Repository
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import slick.jdbc.meta.MTable
import scala.compat.Platform
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import slick.dbio
import slick.driver.JdbcActionComponent
import slick.driver.JdbcTypesComponent._
import scala.util.{Failure, Success, Try}
import akka.pattern.{ask, pipe}

case class QueryImportBatchReferences(organisations: Set[String])
case class ReferenceResult(references: Seq[UUID])

class ImportBatchOrgActor(db: Database) extends Actor with ActorLogging {
  implicit val executionContext: ExecutionContext = context.dispatcher
  val table = TableQuery[ImportBatchOrgTable]
  JDBCUtil.createSchemaForTable(table, db)

  override def receive: Receive = {
    case i: ImportBatchOrg =>
      log.info(s"Saving import batch organisation ${i.oid}!")
      val entry: (UUID, String, Long) = toRow(i)
      Try(run(table.insertOrUpdate(entry)))
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
