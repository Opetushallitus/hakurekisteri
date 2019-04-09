package fi.vm.sade.hakurekisteri.rest.support

import akka.actor.ActorSystem
import akka.event.Logging
import fi.vm.sade.hakurekisteri.integration.ExecutorUtil
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.storage.repository._
import org.postgresql.util.PSQLException
import slick.ast.BaseTypedType
import slick.jdbc.TransactionIsolation
import slick.jdbc.meta.MTable
import slick.lifted
import support.SureDbLoggingConfig

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.util.control.NonFatal

object JDBCUtil {
  def createSchemaForTable[T <: Table[_]](table: lifted.TableQuery[T], db: Database)(implicit ec: ExecutionContext) {
    lazy val tableName = table.baseTableRow.tableName
    val queryTimeout: Duration = 1.minute

    Await.result(db.run(MTable.getTables(tableName).flatMap((t: Vector[MTable]) => {
      if (t.isEmpty) {
        schemaActionExtensionMethods(tableQueryToTableQueryExtensionMethods(table).schema).create
      } else {
        DBIO.successful(())
      }
    })), queryTimeout)

  }
}

class JDBCJournal[R <: Resource[I, R], I, T <: JournalTable[R, I, _]](val table: lifted.TableQuery[T], val dbLoggingConfig: SureDbLoggingConfig = SureDbLoggingConfig())
                                                                     (implicit val db: Database, val idType: BaseTypedType[I], implicit val system: ActorSystem)
  extends Journal[R, I] {

  implicit val ec: ExecutionContext = ExecutorUtil.createExecutor(8, getClass.getSimpleName)
  val log = Logging.getLogger(system, this)
  lazy val tableName = table.baseTableRow.tableName
  val queryTimeout: Duration = 1.minute
  JDBCUtil.createSchemaForTable(table, db)

  log.info(s"started ${getClass.getSimpleName} with table $tableName")

  override def addModification(o: Delta[R, I]): Unit =
    runAsSerialized(10, 5.milliseconds, s"Storing $o", addModificationAction(o)).left.foreach(throw _)

  def addUpdate(r: R with Identified[I]): DBIO[R with Identified[I]] =
    addModificationAction(Updated(r)).andThen(DBIO.successful(r))

  def addModificationAction(o: Delta[R, I]): DBIO[Delta[R, I]] =
    DBIO.seq(
      latestResources.filter(_.resourceId === o.id).map(_.current).update(false),
      table += o
    ).andThen(DBIO.successful(o))

  override def journal(latestQuery: Option[Long]): Seq[Delta[R, I]] = latestQuery match {
    case None => Await.result(db.run(latestResources.result), queryTimeout)
    case Some(lat) => Await.result(db.run(latestResources.filter(_.inserted >= lat).result), queryTimeout)
  }

  val latestResources = {
    table.filter(_.current)
  }

  def runAsSerialized[A](retries: Int, wait: Duration, description: String, action: DBIO[A]): Either[Throwable, A] = {
    val SERIALIZATION_VIOLATION = "40001"
    try {
      Right(Await.result(db.run(action.transactionally.withTransactionIsolation(TransactionIsolation.Serializable)), queryTimeout))
    } catch {
      case e: PSQLException if e.getSQLState == SERIALIZATION_VIOLATION =>
        if (retries > 0) {
          log.warning(s"$description failed because of an concurrent action, retrying after $wait ms")
          Thread.sleep(wait.toMillis)
          runAsSerialized(retries - 1, wait + wait, description, action)
        } else {
          Left(new RuntimeException(s"$description failed because of an concurrent action.", e))
        }
      case NonFatal(e) => Left(e)
    }
  }
}
