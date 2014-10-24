package fi.vm.sade.hakurekisteri.rest.support

import fi.vm.sade.hakurekisteri.storage.repository._
import fi.vm.sade.hakurekisteri.storage.{ResourceService, Identified}
import HakurekisteriDriver.simple._
import fi.vm.sade.hakurekisteri.storage.repository.Deleted
import scala.concurrent.{ExecutionContext, Future}
import scala.slick.ast.BaseTypedType
import scala.slick.lifted


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

  override def save(t: R): R with Identified[I] =  {
    val identified = t.identify
    journal.addModification(Updated[R,I](identified))
    identified
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
