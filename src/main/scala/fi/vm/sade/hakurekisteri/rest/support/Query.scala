package fi.vm.sade.hakurekisteri.rest.support

import org.joda.time.DateTime

trait Query[T] {
  val muokattuJalkeen: Option[DateTime] = None
  def productIterator: Iterator[Any]
}
