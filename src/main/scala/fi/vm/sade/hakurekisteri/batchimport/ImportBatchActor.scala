package fi.vm.sade.hakurekisteri.batchimport

import java.util.UUID
import java.util.concurrent.Executors

import akka.dispatch.ExecutionContexts
import akka.pattern.{ask, pipe}
import fi.vm.sade.hakurekisteri.rest.support
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.simple._
import fi.vm.sade.hakurekisteri.rest.support.{JDBCJournal, JDBCRepository, JDBCService}
import fi.vm.sade.hakurekisteri.storage.{Identified, ResourceActor}
import fi.vm.sade.hakurekisteri.storage.repository.{Updated, Delta}

import scala.concurrent.ExecutionContext
import scala.slick.lifted


object BatchState extends Enumeration {
  type BatchState = Value
  val READY = Value("READY")
  val DONE = Value("DONE")
  val FAILED = Value("FAILED")
}

import fi.vm.sade.hakurekisteri.batchimport.BatchState.BatchState

case class BatchesBySource(source: String)
case object AllBatchStatuses

class ImportBatchActor(val journal: JDBCJournal[ImportBatch, UUID, ImportBatchTable], poolSize: Int) extends ResourceActor[ImportBatch, UUID] with JDBCRepository[ImportBatch, UUID, ImportBatchTable] with JDBCService[ImportBatch, UUID, ImportBatchTable] {
  implicit val batchStateColumnType = MappedColumnType.base[BatchState, String]({ c => c.toString }, { s => BatchState.withName(s)})

  override val dbQuery: PartialFunction[support.Query[ImportBatch], lifted.Query[ImportBatchTable, Delta[ImportBatch, UUID], Seq]]  = {
    case ImportBatchQuery(None, None, None, maxCount) =>
      val q = all
      maxCount.map(count => all.take(count)).getOrElse(q)
    case ImportBatchQuery(externalId, state, batchType, maxCount) =>
      val q = all.filter(i => matchExternalId(externalId)(i) && matchState(state)(i) && matchBatchType(batchType)(i))
      maxCount.map(count => q.take(count)).getOrElse(q)
  }

  def matchExternalId(externalId: Option[String])(i: ImportBatchTable): Column[Option[Boolean]] = externalId match {
    case Some(e) => i.externalId === Option(e)
    case None => Some(true)
  }

  def matchBatchType(batchType: Option[String])(i: ImportBatchTable): Column[Boolean] = batchType match {
    case Some(t) => i.batchType === t
    case None => true
  }

  def matchState(state: Option[BatchState])(i: ImportBatchTable): Column[Boolean] = state match {
    case Some(s) => i.state === s
    case None => true
  }

  def importBatchWithoutData(t: (UUID, Option[String], String, String, BatchState, ImportStatus, Long)): ImportBatch = ImportBatch(
    data = <empty></empty>,
    externalId = t._2,
    batchType = t._3,
    source = t._4,
    state = t._5,
    status = t._6
  ).identify(t._1)

  def allWithoutData = journal.db.withSession {
    implicit session =>
      (for (
        i <- all
      ) yield (i.resourceId, i.externalId, i.batchType, i.source, i.state, i.status, i.inserted)).sortBy(_._6).list.map(importBatchWithoutData)
  }

  def bySource(source: String) = {
    journal.db.withSession {
      implicit session =>
        (for (
          i <- all.filter(_.source === source)
        ) yield (i.resourceId, i.externalId, i.batchType, i.source, i.state, i.status, i.inserted)).sortBy(_._6).list.map(importBatchWithoutData)
    }
  }

  override implicit val executionContext: ExecutionContext = context.dispatcher

  override val dbExecutor = ExecutionContexts.fromExecutor(Executors.newFixedThreadPool(poolSize))

  override def deduplicationQuery(i: ImportBatch)(t: ImportBatchTable): lifted.Column[Boolean] = t.source === i.source && t.batchType === i.batchType && t.externalId.getOrElse("") === i.externalId.getOrElse("")

  override def receive: Receive = super.receive.orElse {
    case BatchesBySource(source) => sender ! bySource(source)

    case AllBatchStatuses => sender ! allWithoutData
  }
}


case class ImportBatchQuery(externalId: Option[String], state: Option[BatchState], batchType: Option[String], maxCount: Option[Int] = None) extends support.Query[ImportBatch]

