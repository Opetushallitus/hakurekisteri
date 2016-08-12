package fi.vm.sade.hakurekisteri.rest.support

import fi.vm.sade.hakurekisteri.storage.repository._
import fi.vm.sade.hakurekisteri.storage.{Identified, ResourceService}
import fi.vm.sade.hakurekisteri.storage.repository.Deleted

import scala.concurrent.{Await, ExecutionContext, Future}
import HakurekisteriDriver.api._
import slick.ast.BaseTypedType
import slick.lifted

import scala.concurrent.duration._


trait JDBCRepository[R <: Resource[I, R], I, T <: JournalTable[R, I, _]] extends Repository[R,I]  {

  val journal: JDBCJournal[R,I,T]

  implicit val idType: BaseTypedType[I] = journal.idType

  override def delete(id: I, source: String): Unit = journal.addModification(Deleted[R,I](id, source))

  override def cursor(t: R): Any = ???

  val all = journal.latestResources.filter(_.deleted === false)

  def latest(id: I): lifted.Query[T, Delta[R, I], Seq] = all.filter((item) => item.resourceId === id)

  override def get(id: I): Option[R with Identified[I]] = Await.result(journal.db.run(latest(id).result.headOption), 10.seconds).collect {
    case Updated(res) => res
  }

  override def listAll(): Seq[R with Identified[I]] = Await.result(journal.db.run(all.result), 1.minute).collect {
    case Updated(res) => res
  }

  override def count: Int = Await.result(journal.db.run(all.length.result), 1.minute)

  def doSave(t: R with Identified[I]): R with Identified[I] = {
    journal.addModification(Updated[R, I](t))
    t
  }

  def deduplicationQuery(i: R)(t: T): lifted.Rep[Boolean]

  def deduplicate(i: R): Option[R with Identified[I]] = Await.result(journal.db.run(all.filter(deduplicationQuery(i)).result), 30.seconds).collect {
    case Updated(res) => res
  }.headOption

  override def save(t: R): R with Identified[I] = {
    deduplicate(t) match {
      case Some(i) => doSave(t.identify(i.id))
      case None => doSave(t.identify)
    }
  }
  override def insert(t: R): R with Identified[I] = {
    deduplicate(t) match {
      case Some(i) => i
      case None => doSave(t.identify)
    }
  }
}

trait JDBCService[R <: Resource[I, R], I, T <: JournalTable[R, I, _]] extends ResourceService[R,I] { this: JDBCRepository[R,I,T] =>
  val dbExecutor:ExecutionContext

  override def findBy(q: Query[R]): Future[Seq[R with Identified[I]]] = {
    if (q.muokattuJalkeen.isDefined) {
      throw new NotImplementedError("muokattuJalkeen not implemented in JDBCService")
    }

    dbQuery.lift(q).map(q => {
      journal.db.run(q.result).map(_.collect { case Updated(res) => res })(dbExecutor)
    }).getOrElse(Future.successful(Seq()))
  }

  val dbQuery: PartialFunction[Query[R], lifted.Query[T, Delta[R,I], Seq]]
}
