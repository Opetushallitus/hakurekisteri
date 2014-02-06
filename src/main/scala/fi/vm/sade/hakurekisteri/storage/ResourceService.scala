package fi.vm.sade.hakurekisteri.storage

import fi.vm.sade.hakurekisteri.rest.support.Query
import scala.concurrent.Future


trait ResourceService[T] {

  def findBy(q: Query[T]):Future[Seq[T with Identified]]

}
