package fi.vm.sade.hakurekisteri.storage.repository

import java.util.UUID

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
  var reverseStore:Map[T, Seq[UUID]] = Map()
  var cursor:Map[Int, (Long, String)] = Map()

  def updateCursor(t:T, id:UUID) = (id, Platform.currentTime, cursor(t)) match {
    case (resourceId, time, Some((curtime, curid))) if resourceId.toString == curid  && time == curtime =>  (time, resourceId.toString + "#a")
    case (resourceId, time, Some((curtime, curid))) if curid.startsWith(resourceId.toString) && time == curtime => (time, curid + "a")
    case (resourceId, time, _) => time -> id.toString

  }

  override def cursor(t: T): Option[(Long, String)] = {
    cursor.get(t.hashCode % 16384)

  }

  def save(o: T ): T with Identified = {

    val oid = reverseStore.get(o).flatMap((ids) => ids.headOption.map((id) => o.identify(id)) ).getOrElse(identify(o))
    val old = store.get(oid.id)
    val result = saveIdentified(oid)
    cursor = cursor + ((o.hashCode % 16384) -> updateCursor(o,oid.id))
    old.foreach((item) => cursor = cursor + ((item.hashCode % 16384) -> updateCursor(item,oid.id)))
    result
  }

  protected def saveIdentified(oid: T with Identified) = {
    val old = store.get(oid.id)
    store = store + (oid.id -> oid)
    val newSeq = reverseStore.get(oid).map(oid.id +: _).getOrElse(Seq(oid.id))
    reverseStore = reverseStore + (oid -> newSeq)
    index(old, Some(oid))
    oid
  }

  def index(old: Option[T with Identified], oid: Option[T with Identified]) {}

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
      index(deleted, None)
      deleted.foreach((item) => cursor = cursor + (item.hashCode % 16384 -> updateCursor(item, id)))
    }
  }

  def deleteFromStore(id:UUID) = {
    val item = store.get(id)
    store = store - id
    item.foreach((deleted) => {

      val newSeq = reverseStore.get(deleted).map(_.filter(_ != id)).getOrElse(Seq())
      if (newSeq.isEmpty) reverseStore = reverseStore - deleted
      else reverseStore = reverseStore + (deleted -> newSeq)

    })
    item
  }

}









