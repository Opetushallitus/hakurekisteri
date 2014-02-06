package fi.vm.sade.hakurekisteri.storage

import fi.vm.sade.hakurekisteri.rest.support.Query
import scala.concurrent.{ExecutionContext, Future}
import fi.vm.sade.hakurekisteri.opiskelija.Opiskelija


trait ResourceService[T] {

  implicit val executionContext:ExecutionContext

  val finder: PartialFunction[Query[T], Seq[T with Identified]]

  def findBy(o: Query[T]):Future[Seq[T with Identified]] = {
    Future {
      finder(o)
    }
  }



}
