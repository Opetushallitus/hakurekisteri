package fi.vm.sade.hakurekisteri.batchimport

import fi.vm.sade.hakurekisteri.storage.{Identified, ResourceActor}
import java.util.UUID
import fi.vm.sade.hakurekisteri.rest.support.{JDBCJournal, JDBCService, JDBCRepository}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.simple._
import scala.concurrent.ExecutionContext
import akka.dispatch.ExecutionContexts
import java.util.concurrent.Executors
import fi.vm.sade.hakurekisteri.rest.support
import scala.slick.lifted
import fi.vm.sade.hakurekisteri.storage.repository.Delta


class ImportBatchActor(val journal:JDBCJournal[ImportBatch, UUID, ImportBatchTable], poolSize: Int) extends ResourceActor[ImportBatch, UUID] with JDBCRepository[ImportBatch, UUID, ImportBatchTable] with JDBCService[ImportBatch, UUID, ImportBatchTable] {
  override val dbQuery: PartialFunction[support.Query[ImportBatch], lifted.Query[ImportBatchTable, Delta[ImportBatch, UUID]]]  = {
    case ImportBatchQuery(Some(externalId)) => all.filter(_.externalId === Option(externalId))
    case ImportBatchQuery(None) => all
  }
  //override implicit val executionContext: ExecutionContext = context.dispatcher

  override val dbExecutor = ExecutionContexts.fromExecutor(Executors.newFixedThreadPool(poolSize))
}

case class ImportBatchQuery(externalId: Option[String]) extends support.Query[ImportBatch]
