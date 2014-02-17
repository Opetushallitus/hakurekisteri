package fi.vm.sade.hakurekisteri.storage.repository

import java.util.UUID

import scala.slick.lifted.AbstractTable
import fi.vm.sade.hakurekisteri.storage.Identified

trait Repository[T] {

  def save(t:T):T with Identified

  def listAll():Seq[T with Identified]

}



trait InMemRepository[T] extends Repository[T] {

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


}









