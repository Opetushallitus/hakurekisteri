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

  def cursor(t:T): Any

  def delete(id: UUID)
}



trait InMemRepository[T <: Resource] extends Repository[T] {

  var store:Map[UUID,T with Identified] = Map()
  var cursor:Map[Int, (Long, String)] = Map()

  def updateCursor(t:T, id:UUID) = (id, Platform.currentTime, cursor(t)) match {
    case (id, time, Some((curtime, curid))) if id.toString == curid  && time == curtime =>  (time, id.toString + "#a")
    case (id, time, Some((curtime, curid))) if curid.startsWith(id.toString) && time == curtime => (time, curid + "a")
    case (id, time, cursor) => (time -> id.toString)

  }

  override def cursor(t: T): Option[(Long, String)] = {
    cursor.get(t.hashCode % 1024)

  }

  def save(o: T ): T with Identified = {
    val oid = identify(o)
    val result = saveIdentified(oid)
    cursor = cursor + ((o.hashCode % 1024) -> updateCursor(o,oid.id))
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


  override def delete(id:UUID): Unit = {
    if (store.contains(id)) {
      val deleted = deleteFromStore(id)
      deleted.foreach((item) => cursor = cursor + (item.hashCode % 1024 -> updateCursor(item, id)))
    }
  }

  def deleteFromStore(id:UUID) = {
    val item = store.get(id)
    store = store - id
    item
  }

}









