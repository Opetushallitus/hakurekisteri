package fi.vm.sade.hakurekisteri.rest.support

import fi.vm.sade.hakurekisteri.integration.henkilo.PersonOidsWithAliases
import org.joda.time.DateTime

import scala.concurrent.Future

trait Query[T] {
  val muokattuJalkeen: Option[DateTime] = None
  def productIterator: Iterator[Any]
}

trait QueryWithPersonAliasesResolver[T] extends Query[T] {
  def fetchPersonAliases: Future[PersonOidsWithAliases]
  def createQueryWithAliases(personOidsWithAliases: PersonOidsWithAliases): Query[T]
}
