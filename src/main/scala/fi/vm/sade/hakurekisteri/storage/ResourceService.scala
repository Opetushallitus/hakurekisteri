package fi.vm.sade.hakurekisteri.storage

import fi.vm.sade.hakurekisteri.rest.support.Query
import scala.concurrent.{ExecutionContext, Future}
import fi.vm.sade.hakurekisteri.storage.repository.Repository


trait ResourceService[T] { this: Repository[T] =>

  implicit val executionContext:ExecutionContext

  val matcher: PartialFunction[Query[T], (T with Identified) => Boolean]

  def check(q: Query[T])(item: T with Identified): Future[Option[T with Identified]] = {
    Future{
      if (matcher(q)(item)) Some(item) else None
    }
  }

  def findBy(o: Query[T]):Future[Seq[T with Identified]] = {
    Future.traverse(listAll())(check(o)).map(_.collect  {case Some(a) => a})
  }



}
