package fi.vm.sade.hakurekisteri.storage

import fi.vm.sade.hakurekisteri.rest.support.Query
import scala.concurrent.{ExecutionContext, Future}
import fi.vm.sade.hakurekisteri.storage.repository.Repository


trait ResourceService[T, I] { this: Repository[T, I] =>

  implicit val executionContext:ExecutionContext

  val matcher: PartialFunction[Query[T], (T with Identified[I]) => Boolean]


  val emptyQuery: PartialFunction[Query[T], Boolean] = Map()



  def check(q: Query[T])(item: T with Identified[I]): Future[Option[T with Identified[I]]] = {
    Future{
      if (matcher(q)(item)) Some(item) else None
    }
  }

  val optimize:PartialFunction[Query[T], Future[Seq[T with Identified[I]]]] = Map()

  def findBy(o: Query[T]):Future[Seq[T with Identified[I]]] = {

    val current = listAll()
    val empty = emptyQuery.lift(o).getOrElse(false)
    if (empty)
      Future.successful(current)
    else
      optimize.applyOrElse(o, executeQuery(current))
  }


  def executeQuery(current: Seq[T with Identified[I]])( o: Query[T]): Future[Seq[T with Identified[I]]] = {
    Future.traverse(current)(check(o)).map(_.collect {
      case Some(a: T with Identified[I]) => a
    })
  }
}


case class DeduplicationQuery[T](resource:T) extends Query[T]
