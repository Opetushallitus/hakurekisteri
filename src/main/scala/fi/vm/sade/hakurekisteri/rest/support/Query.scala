package fi.vm.sade.hakurekisteri.rest.support

import fi.vm.sade.hakurekisteri.integration.henkilo.PersonOidsWithAliases
import org.joda.time.DateTime

trait Query[T] {
  val muokattuJalkeen: Option[DateTime] = None
  def productIterator: Iterator[Any]
}

trait QueryWithPersonOid[T] extends Query[T] {
  def createQueryWithAliases(personOidsWithAliases: PersonOidsWithAliases): Query[T]

  val henkilo: Option[String]
}
