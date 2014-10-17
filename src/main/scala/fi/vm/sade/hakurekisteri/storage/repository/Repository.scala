package fi.vm.sade.hakurekisteri.storage.repository


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



trait InMemRepository[T <: Resource[I, T], I] extends Repository[T, I] {

  var store:Map[I,T with Identified[I]] = Map()
  var reverseStore:Map[AnyRef, Set[I]] = Map()
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

    val oid = reverseStore.get(o.core).flatMap((ids) => ids.headOption.map((id) => o.identify(id)) ).getOrElse(o.identify)
    val old = store.get(oid.id)
    val result = saveIdentified(oid)
    cursor = cursor + ((o.hashCode % 16384) -> updateCursor(o,oid.id))
    old.foreach((item) => cursor = cursor + ((item.hashCode % 16384) -> updateCursor(item,oid.id)))
    result
  }

  protected def saveIdentified(oid: T with Identified[I]) = {
    val old = store.get(oid.id)
    store = store + (oid.id -> oid)
    val core = getCore(oid)
    val newSeq = reverseStore.get(core).map((s) => s + oid.id).getOrElse(Set(oid.id))
    reverseStore = reverseStore + (core -> newSeq)
    index(old, Some(oid))
    oid
  }

  def getCore(o: T): AnyRef = o.core

  def index(old: Option[T with Identified[I]], oid: Option[T with Identified[I]]) {}


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
      val core = getCore(deleted)
      val newSeq = reverseStore.get(core).map(_.filter(_ != id)).getOrElse(Set())
      if (newSeq.isEmpty) reverseStore = reverseStore - core
      else reverseStore = reverseStore + (core -> newSeq)

    })
    item
  }

}









