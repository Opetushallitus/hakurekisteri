package fi.vm.sade.hakurekisteri.batchimport

import java.util.UUID
import java.util.concurrent.Executors

import akka.dispatch.ExecutionContexts
import akka.pattern.{ask, pipe}
import fi.vm.sade.hakurekisteri.rest.support
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.simple._
import fi.vm.sade.hakurekisteri.rest.support.{JDBCJournal, JDBCRepository, JDBCService}
import fi.vm.sade.hakurekisteri.storage.ResourceActor
import fi.vm.sade.hakurekisteri.storage.repository.Delta

import scala.concurrent.ExecutionContext
import scala.slick.lifted


object BatchState extends Enumeration {
  type BatchState = Value
  val READY = Value("READY")
  val DONE = Value("DONE")
  val FAILED = Value("FAILED")
}

import fi.vm.sade.hakurekisteri.batchimport.BatchState.BatchState

class ImportBatchActor(val journal: JDBCJournal[ImportBatch, UUID, ImportBatchTable], poolSize: Int) extends ResourceActor[ImportBatch, UUID] with JDBCRepository[ImportBatch, UUID, ImportBatchTable] with JDBCService[ImportBatch, UUID, ImportBatchTable] {
  implicit val batchStateColumnType = MappedColumnType.base[BatchState, String]({ c => c.toString }, { s => BatchState.withName(s)})

  override val dbQuery: PartialFunction[support.Query[ImportBatch], lifted.Query[ImportBatchTable, Delta[ImportBatch, UUID], Seq]]  = {
    case ImportBatchQuery(None, None, None) => all
    case ImportBatchQuery(externalId, state, batchType) => all.filter(i => matchExternalId(externalId)(i) && matchState(state)(i) && matchBatchType(batchType)(i))
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

  override implicit val executionContext: ExecutionContext = context.dispatcher

  override val dbExecutor = ExecutionContexts.fromExecutor(Executors.newFixedThreadPool(poolSize))
}

case class ImportBatchQuery(externalId: Option[String], state: Option[BatchState], batchType: Option[String]) extends support.Query[ImportBatch]

