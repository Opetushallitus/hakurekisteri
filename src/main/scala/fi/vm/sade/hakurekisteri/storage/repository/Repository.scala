package fi.vm.sade.hakurekisteri.storage.repository

import java.util.UUID

import scala.slick.lifted.AbstractTable
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.rest.support.Resource

trait Repository[T] {

  def save(t:T):T with Identified

  def listAll():Seq[T with Identified]

  def get(id: UUID): Option[T with Identified]

}



trait InMemRepository[T <: Resource] extends Repository[T] {

  var store:Map[UUID,T with Identified] = Map()

  def save(o: T ): T with Identified = {
    val oid = identify(o)
    saveIdentified(oid)
  }

  protected def saveIdentified(oid: T with Identified) = {
    store = store + (oid.id -> oid)
    oid
  }

  def identify(o: T): T with Identified

  def listAll(): Seq[T with Identified] = {
    store.values.toSeq
  }

  def get(id:UUID): Option[T with Identified]   = {
    store.get(id)
  }


}









