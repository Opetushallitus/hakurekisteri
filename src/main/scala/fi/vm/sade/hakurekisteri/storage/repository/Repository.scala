package fi.vm.sade.hakurekisteri.storage.repository

import java.util.UUID

import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.rest.support.Resource
import scala.compat.Platform

trait Repository[T, I] {

  def save(t:T):T with Identified[I]

  def listAll():Seq[T with Identified[I]]

  def get(id: I): Option[T with Identified[I]]

  def cursor(t:T): Any

  def delete(id: I, source: String)
}



trait InMemRepository[T <: Resource[I], I] extends Repository[T, I] {

  var store:Map[I,T with Identified[I]] = Map()
  var reverseStore:Map[T, Set[I]] = Map()
  var cursor:Map[Int, (Long, String)] = Map()

  def updateCursor(t:T, id:I) = (id, Platform.currentTime, cursor(t)) match {
    case (resourceId, time, Some((curtime, curid))) if resourceId.toString == curid  && time == curtime =>  (time, resourceId.toString + "#a")
    case (resourceId, time, Some((curtime, curid))) if curid.startsWith(resourceId.toString) && time == curtime => (time, curid + "a")
    case (resourceId, time, _) => time -> id.toString

  }

  override def cursor(t: T): Option[(Long, String)] = {
    cursor.get(t.hashCode % 16384)

  }

  def save(o: T ): T with Identified[I] = {

    val oid = reverseStore.get(o).flatMap((ids) => ids.headOption.map((id) => o.identify(id)) ).getOrElse(identify(o))
    val old = store.get(oid.id)
    val result = saveIdentified(oid)
    cursor = cursor + ((o.hashCode % 16384) -> updateCursor(o,oid.id))
    old.foreach((item) => cursor = cursor + ((item.hashCode % 16384) -> updateCursor(item,oid.id)))
    result
  }

  protected def saveIdentified(oid: T with Identified[I]) = {
    val old = store.get(oid.id)
    store = store + (oid.id -> oid)
    val newSeq = reverseStore.get(oid).map((s) => s + oid.id).getOrElse(Set(oid.id))
    reverseStore = reverseStore + (oid -> newSeq)
    index(old, Some(oid))
    oid
  }

  def index(old: Option[T with Identified[I]], oid: Option[T with Identified[I]]) {}

  def identify(o: T): T with Identified[I]

  def listAll(): Seq[T with Identified[I]] = {
    store.values.toSeq
  }

  def get(id:I): Option[T with Identified[I]]   = {
    store.get(id)
  }


  override def delete(id:I,source :String): Unit = {
    if (store.contains(id)) {
      val deleted = deleteFromStore(id, source :String)
      index(deleted, None)
      deleted.foreach((item) => cursor = cursor + (item.hashCode % 16384 -> updateCursor(item, id)))
    }
  }

  def deleteFromStore(id:I, source: String) = {
    val item = store.get(id)
    store = store - id
    item.foreach((deleted) => {

      val newSeq = reverseStore.get(deleted).map(_.filter(_ != id)).getOrElse(Set())
      if (newSeq.isEmpty) reverseStore = reverseStore - deleted
      else reverseStore = reverseStore + (deleted -> newSeq)

    })
    item
  }

}









