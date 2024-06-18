package fi.vm.sade.hakurekisteri.batchimport

import java.util.UUID
import java.util.concurrent.Executors

import akka.actor.{Actor, ActorRef, Props}
import akka.dispatch.ExecutionContexts
import akka.pattern.{ask, pipe}
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.batchimport.ImportBatchTable.ImportBatchRow
import fi.vm.sade.hakurekisteri.integration.ExecutorUtil
import fi.vm.sade.hakurekisteri.rest.support
import fi.vm.sade.hakurekisteri.rest.support.{JDBCJournal, JDBCRepository, JDBCService}
import fi.vm.sade.hakurekisteri.storage.{Identified, ResourceActor}
import fi.vm.sade.hakurekisteri.storage.repository.{Deleted, Delta, Insert, Updated}

import scala.concurrent.{Await, ExecutionContext, Future}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import slick.dbio.Effect.{All, Read}

import scala.concurrent.duration._
import fi.vm.sade.hakurekisteri.storage.repository.{Deleted, _}
import fi.vm.sade.hakurekisteri.storage.{Identified, ResourceService}
import slick.ast.BaseTypedType
import slick.lifted

object BatchState extends Enumeration {
  type BatchState = Value
  val READY = Value("READY")
  val DONE = Value("DONE")
  val FAILED = Value("FAILED")
  val PROCESSING = Value("PROCESSING")
}

import fi.vm.sade.hakurekisteri.batchimport.BatchState.BatchState

case class BatchesByReference(reference: Seq[UUID])
case object AllBatchStatuses
case class Reprocess(id: UUID)
case class WrongBatchStateException(s: BatchState) extends Exception(s"illegal batch state $s")
object BatchNotFoundException extends Exception("batch not found")
case class ImportBatchQuery(
  externalId: Option[String],
  state: Option[BatchState],
  batchType: Option[String],
  maxCount: Option[Int] = None
) extends support.Query[ImportBatch]

@Deprecated
class ImportBatchActor(
  val journal: JDBCJournal[ImportBatch, UUID, ImportBatchTable],
  poolSize: Int,
  config: Config
) extends ResourceActor[ImportBatch, UUID](config)
    with JDBCRepository[ImportBatch, UUID, ImportBatchTable]
    with JDBCService[ImportBatch, UUID, ImportBatchTable] {

  implicit val batchStateColumnType =
    MappedColumnType.base[BatchState, String]({ c => c.toString }, { s => BatchState.withName(s) })

  override val dbQuery
    : PartialFunction[support.Query[ImportBatch], Either[Throwable, DBIOAction[Seq[
      Delta[ImportBatch, UUID]
    ], Streaming[Delta[ImportBatch, UUID]], All]]] = {
    case ImportBatchQuery(None, None, None, maxCount) =>
      val q = all
      Right(maxCount.map(count => q.take(count)).getOrElse(q).result)
    case ImportBatchQuery(externalId, state, batchType, maxCount) =>
      val q = all.filter(i =>
        matchExternalId(externalId)(i) && matchState(state)(i) && matchBatchType(batchType)(i)
      )
      Right(maxCount.map(count => q.take(count)).getOrElse(q).result)
  }

  def matchExternalId(externalId: Option[String])(i: ImportBatchTable): Rep[Option[Boolean]] =
    externalId match {
      case Some(e) => i.externalId === Option(e)
      case None    => Some(true)
    }

  def matchBatchType(batchType: Option[String])(i: ImportBatchTable): Rep[Boolean] =
    batchType match {
      case Some(t) => i.batchType === t
      case None    => true
    }

  def matchState(state: Option[BatchState])(i: ImportBatchTable): Rep[Boolean] = state match {
    case Some(s) => i.state === s
    case None    => true
  }

  def importBatchWithoutData(t: Delta[ImportBatch, UUID]): ImportBatch with Identified[UUID] =
    t match {
      case Updated(i) =>
        i.copy(data = <empty></empty>).identify(i.id)
      case Insert(i) =>
        i.copy(data = <empty></empty>).identify(i.id)
      case Deleted(id, _) =>
        throw new IllegalStateException(s"cannot read deleted delta into a row, id: $id")
    }

  def allWithoutData: Future[Seq[ImportBatch with Identified[UUID]]] = {
    journal.db.run(all.sortBy(_.status).result.map(_.map(importBatchWithoutData)))
  }

  override implicit val executionContext: ExecutionContext = ExecutorUtil.createExecutor(
    config.integrations.asyncOperationThreadPoolSize,
    getClass.getSimpleName
  )

  override val dbExecutor = ExecutionContexts.fromExecutor(Executors.newFixedThreadPool(poolSize))

  override def deduplicationQuery(i: ImportBatch)(t: ImportBatchTable): lifted.Rep[Boolean] =
    t.source === i.source && t.batchType === i.batchType && t.externalId.getOrElse(
      ""
    ) === i.externalId.getOrElse("")

  override def receive: Receive = super.receive.orElse {
    case BatchesByReference(references) =>
      sender ! getAll(references)
    case AllBatchStatuses =>
      allWithoutData.pipeTo(sender)

    case Reprocess(id) =>
      val parent = self
      val caller = sender()
      context.actorOf(Props(new ReprocessBatchActor(id, parent, caller)))
  }

  class ReprocessBatchActor(id: UUID, importBatchActor: ActorRef, caller: ActorRef) extends Actor {
    override def preStart(): Unit = {
      importBatchActor ! id
      super.preStart()
    }

    override def receive: Actor.Receive = {
      case Some(b: ImportBatch with Identified[UUID @unchecked]) =>
        if (b.state == BatchState.DONE || b.state == BatchState.FAILED) {
          importBatchActor ! b
            .copy(
              state = BatchState.READY,
              status = b.status.copy(
                processedTime = None,
                messages = Map(),
                successRows = None,
                failureRows = None
              )
            )
            .identify(b.id)
        } else {
          Future.failed(WrongBatchStateException(b.state)).pipeTo(caller)(ActorRef.noSender)
          context.stop(self)
        }

      case None =>
        Future.failed(BatchNotFoundException).pipeTo(caller)(ActorRef.noSender)
        context.stop(self)

      case b: ImportBatch with Identified[UUID @unchecked] =>
        caller.!(b.id)(ActorRef.noSender)
        context.stop(self)
    }
  }
}
