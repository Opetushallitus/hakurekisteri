package fi.vm.sade.hakurekisteri.storage

import akka.event.LoggingAdapter
import fi.vm.sade.hakurekisteri.Config
import fi.vm.sade.hakurekisteri.integration.ExecutorUtil
import fi.vm.sade.hakurekisteri.rest.support.{Query, QueryWithPersonOid, Resource}

import scala.concurrent.{ExecutionContext, Future}
import fi.vm.sade.hakurekisteri.storage.repository._

trait ResourceService[T, I] {
  def findBy(o: Query[T]): Future[Seq[T with Identified[I]]]
  def findByWithPersonAliases(o: QueryWithPersonOid[T]): Future[Seq[T with Identified[I]]] = findBy(
    o
  )
}

trait InMemQueryingResourceService[T <: Resource[I, T], I] extends ResourceService[T, I] {
  this: JournaledRepository[T, I] =>

  val matcher: PartialFunction[Query[T], (T with Identified[I]) => Boolean]
  val config: Config

  implicit val executionContext: ExecutionContext = ExecutorUtil.createExecutor(
    config.integrations.asyncOperationThreadPoolSize,
    getClass.getSimpleName
  )

  val logger: LoggingAdapter

  val emptyQuery: PartialFunction[Query[T], Boolean] = Map()

  def check(q: Query[T])(item: T with Identified[I]): Future[Option[T with Identified[I]]] = {
    Future {
      if (matcher(q)(item)) Some(item) else None
    }
  }

  val optimize: PartialFunction[Query[T], Future[Seq[T with Identified[I]]]] = Map()

  def findBy(o: Query[T]): Future[Seq[T with Identified[I]]] = {
    val current = listAll()
    lazy val allFieldsNone = o.productIterator.forall {
      case None => true
      case _    => false
    }
    if (emptyQuery.lift(o).getOrElse(allFieldsNone))
      Future { current }
    else
      optimize.applyOrElse(o, executeQuery(current))
  }

  private def idsModifiedSince(since: Long): Set[I] = {
    this.journal
      .journal(Some(since))
      .map {
        case Updated(t)     => t.id
        case Insert(t)      => t.id
        case Deleted(id, _) => id
      }
      .toSet
  }

  def executeQuery(
    current: Seq[T with Identified[I]]
  )(query: Query[T]): Future[Seq[T with Identified[I]]] = {
    val resources = query.muokattuJalkeen match {
      case Some(time) =>
        val modifiedIds = idsModifiedSince(time.getMillis)
        current filter ((r) => modifiedIds.contains(r.id))
      case None => current
    }
    Future.traverse(resources)(check(query)) map (_.flatten)
  }
}

case class DeduplicationQuery[T](resource: T) extends Query[T]
