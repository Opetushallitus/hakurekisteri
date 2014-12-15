package fi.vm.sade.hakurekisteri.rest.support

import fi.vm.sade.hakurekisteri.storage.repository._
import fi.vm.sade.hakurekisteri.storage.{ResourceService, Identified}
import HakurekisteriDriver.simple._
import fi.vm.sade.hakurekisteri.storage.repository.Deleted
import scala.concurrent.{ExecutionContext, Future}
import scala.slick.ast.BaseTypedType
import scala.slick.lifted
import fi.vm.sade.hakurekisteri.batchimport.{ImportBatchTable, ImportBatch}
import java.util.UUID


trait JDBCRepository[R <: Resource[I, R], I, T <: JournalTable[R, I, _]] extends Repository[R,I]  {

  val journal: JDBCJournal[R,I,T]

  implicit val idType: BaseTypedType[I] = journal.idType

  override def delete(id: I, source: String): Unit = journal.addModification(Deleted[R,I](id, source))


  override def cursor(t: R): Any = ???

  val all = journal.latestResources.filter(_.deleted === false)

  def latest(id: I) = all.filter((item) => columnExtensionMethods(item.resourceId) === id)

  override def get(id: I): Option[R with Identified[I]] = journal.db withSession(
      implicit session =>
        latest(id).firstOption.collect{ case Updated(res) => res}
    )

  override def listAll(): Seq[R with Identified[I]] = journal.db withSession(
    implicit session =>
      all.list.collect{ case Updated(res) => res}

  )

  def doSave(t: R): R with Identified[I] = t match {
    case current: R with Identified[I] =>
      journal.addModification(Updated[R, I](current))
      current
    case _ =>
      val identified = t.identify
      journal.addModification(Updated[R, I](identified))
      identified
  }

  def deduplicationQuery(i: R)(t: T): lifted.Column[Boolean]

  def deduplicate(i: R): Option[R with Identified[I]] = journal.db withSession(
    implicit session =>
    {
      all.filter(deduplicationQuery(i)).list.collect {
        case Updated(res) => res
      }.headOption
    }
    )

  override def save(t: R): R with Identified[I] = {
    deduplicate(t) match {
      case Some(i) => doSave(t.identify(i.id))
      case None => doSave(t)
    }
  }

}

trait JDBCService[R <: Resource[I, R], I, T <: JournalTable[R, I, _]] extends ResourceService[R,I] { this: JDBCRepository[R,I,T] =>
  val dbExecutor:ExecutionContext

  override def findBy(q: Query[R]): Future[Seq[R with Identified[I]]] =
    Future {
      journal.db withSession {
        implicit session =>
          dbQuery.lift(q).map(_.list.collect{ case Updated(res) => res}).getOrElse(Seq())

      }

    }(dbExecutor)


  val dbQuery: PartialFunction[Query[R], lifted.Query[T, Delta[R,I], Seq]]
}
