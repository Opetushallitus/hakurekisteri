package fi.vm.sade.hakurekisteri.storage

import fi.vm.sade.hakurekisteri.rest.support.Query


trait ResourceService[T] {

  def findBy(q: Query[T]):Seq[T with Identified]

}
