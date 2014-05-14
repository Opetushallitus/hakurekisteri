package fi.vm.sade.hakurekisteri.storage.repository

import java.util.UUID

import scala.slick.lifted.AbstractTable
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.rest.support.Resource
import scala.compat.Platform

trait Repository[T] {

  def save(t:T):T with Identified

  def listAll():Seq[T with Identified]

  def get(id: UUID): Option[T with Identified]

  def cursor: Any

  def delete(id: UUID)
}



trait InMemRepository[T <: Resource] extends Repository[T] {

  var store:Map[UUID,T with Identified] = Map()
  var cursor = Platform.currentTime


  def save(o: T ): T with Identified = {
    val oid = identify(o)
    val result = saveIdentified(oid)
    cursor = Platform.currentTime
    result
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


  override def delete(id:UUID) = {
    deleteFromStore(id)
    cursor = Platform.currentTime

  }

  def deleteFromStore(id:UUID) = {
    store = store - id
  }

}









