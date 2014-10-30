package fi.vm.sade.hakurekisteri.storage

import akka.event.LoggingAdapter
import fi.vm.sade.hakurekisteri.rest.support.Query
import scala.concurrent.{ExecutionContext, Future}
import fi.vm.sade.hakurekisteri.storage.repository.Repository


trait ResourceService[T, I] {

  def findBy(o: Query[T]):Future[Seq[T with Identified[I]]]
}

trait InMemQueryingResourceService[T,I] extends ResourceService[T,I] { this: Repository[T,I] =>

  val matcher: PartialFunction[Query[T], (T with Identified[I]) => Boolean]

  implicit val executionContext: ExecutionContext

  val logger: LoggingAdapter

  val emptyQuery: PartialFunction[Query[T], Boolean] = Map()

  def check(q: Query[T])(item: T with Identified[I]): Future[Option[T with Identified[I]]] = {
    Future{
      if (matcher(q)(item)) Some(item) else None
    }
  }

  val optimize:PartialFunction[Query[T], Future[Seq[T with Identified[I]]]] = Map()

  def findBy(o: Query[T]):Future[Seq[T with Identified[I]]] = {

    val current = listAll()
    lazy val iter = o.productIterator.filter{
      case None => false
      case _ => true
    }
    val empty =  emptyQuery.lift(o).getOrElse(iter.size == 0)
    if (empty)
      Future.successful(current)
    else
      optimize.applyOrElse(o, executeQuery(current))
  }


  def executeQuery(current: Seq[T with Identified[I]])( o: Query[T]): Future[Seq[T with Identified[I]]] = {
    logger.debug(s"got query $o, going through ${current.size} items") // FIXME poista
    Future.traverse(current)(check(o)).map(_.flatten)
  }

}


case class DeduplicationQuery[T](resource:T) extends Query[T]
