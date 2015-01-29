package fi.vm.sade.hakurekisteri.rest.support

trait Query[T] {

  def productIterator: Iterator[Any]

}
