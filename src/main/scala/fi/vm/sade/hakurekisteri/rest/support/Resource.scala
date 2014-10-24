package fi.vm.sade.hakurekisteri.rest.support

import fi.vm.sade.hakurekisteri.storage.Identified
import java.util.UUID


trait Resource[T, R <: Resource[T, R]] {

  val source: String

  def identify(id:T): R with Identified[T]

  def identify:R with Identified[T] = this match {
    case o if o.isInstanceOf[Identified[T]] => o.asInstanceOf[R with Identified[T]]
    case o => o.identify(newId)
  }

  def newId: T

  val core: AnyRef

}

trait UUIDResource[R <: Resource[UUID,R]] extends Resource[UUID, R] {

  def newId = UUID.randomUUID()

}
