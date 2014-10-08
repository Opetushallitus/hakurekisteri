package fi.vm.sade.hakurekisteri.storage

import java.util.UUID
import fi.vm.sade.hakurekisteri.rest.support.Resource


trait Identified[T] {

  val id:T

}


abstract class Identifier[R <: Resource[I] : Manifest,I] {

  def identify(o: R): R with Identified[I] = o match {
    case o: R with Identified[I] => o
    case _ => o.identify(newId)
  }


  def newId: I


}

abstract class UUIDIdentifier[R <: Resource[UUID] : Manifest] extends Identifier[R, UUID] {

   override def newId = UUID.randomUUID()
}
