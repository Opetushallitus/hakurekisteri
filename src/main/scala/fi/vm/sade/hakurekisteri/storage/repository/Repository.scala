package fi.vm.sade.hakurekisteri.storage.repository

import fi.vm.sade.hakurekisteri.integration.henkilo.PersonOidsWithAliases
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.rest.support.Resource

import scala.compat.Platform
import scala.concurrent.Future

trait Repository[T, I] {

  def save(t: T): Future[T with Identified[I]]

  def save(t: T, personOidsWithAliases: PersonOidsWithAliases): Future[T with Identified[I]]

  def insert(t: T, personOidsWithAliases: PersonOidsWithAliases): T with Identified[I]

  def listAll(): Seq[T with Identified[I]]

  def get(id: I): Option[T with Identified[I]]

  def getAll(ids: Seq[I]): Seq[T with Identified[I]]

  def cursor(t: T): Any

  def delete(id: I, source: String)
}

trait InMemRepository[T <: Resource[I, T], I] extends Repository[T, I] {

  var store: Map[I, T with Identified[I]] = Map()
  var reverseStore: Map[AnyRef, List[I]] = Map()
  var cursor: Map[Int, (Long, String)] = Map()

  def updateCursor(t: T, id: I) = (id, Platform.currentTime, cursor(t)) match {
    case (resourceId, time, Some((curtime, curid)))
        if resourceId.toString == curid && time == curtime =>
      (time, resourceId.toString + "#a")
    case (resourceId, time, Some((curtime, curid)))
        if curid.startsWith(resourceId.toString) && time == curtime =>
      (time, curid + "a")
    case (resourceId, time, _) => time -> id.toString
  }

  override def cursor(t: T): Option[(Long, String)] = cursor.get(t.hashCode % 16384)

  override def save(o: T): Future[T with Identified[I]] = {
    Future.successful(reverseStore.get(o.core) match {
      case Some(id :: ids) =>
        store(id) match {
          case old if old == o =>
            old
          case old =>
            val oid = o.identify(id)
            val result = saveIdentified(oid)
            cursor = cursor + ((o.hashCode % 16384) -> updateCursor(o, oid.id))
            Some(old).foreach { (item) =>
              cursor = cursor + ((item.hashCode % 16384) -> updateCursor(item, oid.id))
            }
            result
        }
      case _ =>
        val oid = o.identify
        val result = saveIdentified(oid)
        cursor = cursor + ((o.hashCode % 16384) -> updateCursor(o, oid.id))
        None.foreach((item) =>
          cursor = cursor + ((item.hashCode % 16384) -> updateCursor(item, oid.id))
        )
        result

    })
  }

  override def save(
    o: T,
    personOidsWithAliases: PersonOidsWithAliases
  ): Future[T with Identified[I]] = {
    save(o)
  }

  override def insert(o: T, personOidsWithAliases: PersonOidsWithAliases): T with Identified[I] = {

    val oid = reverseStore
      .get(o.core)
      .flatMap((ids) => ids.headOption.map((id) => o.identify(id)))
      .getOrElse(o.identify)
    val old = store.get(oid.id)
    old match {
      case None => {
        val result = saveIdentified(oid)
        cursor = cursor + ((o.hashCode % 16384) -> updateCursor(o, oid.id))
        //old.foreach((item) => cursor = cursor + ((item.hashCode % 16384) -> updateCursor(item,oid.id)))
        result
      }
      case Some(i) => {
        i
      }
    }

  }

  protected def saveIdentified(oid: T with Identified[I]) = {
    val old = store.get(oid.id)
    store = store + (oid.id -> oid)
    val core = getCore(oid)
    deleteFromDeduplication(old)
    val newSeq = reverseStore.get(core).map((s) => s.+:(oid.id)).getOrElse(List(oid.id))
    reverseStore = reverseStore + (core -> newSeq)
    index(old, Some(oid))
    oid
  }

  def getCore(o: T): AnyRef = o.core

  def index(old: Option[T with Identified[I]], oid: Option[T with Identified[I]]) {}

  def listAll(): Seq[T with Identified[I]] = store.values.toSeq

  def get(id: I): Option[T with Identified[I]] = store.get(id)

  def getAll(ids: Seq[I]): Seq[T with Identified[I]] = ids.map(id => store.get(id)).flatten

  override def delete(id: I, source: String): Unit = {
    if (store.contains(id)) {
      val deleted = deleteFromStore(id, source: String)
      index(deleted, None)
      deleted.foreach((item) => cursor = cursor + (item.hashCode % 16384 -> updateCursor(item, id)))
    }
  }

  def deleteFromDeduplication(old: Option[T with Identified[I]]) = for (
    item <- old;
    oldSeq <- reverseStore.get(getCore(item))
  ) yield {
    val newSeq =
      for (
        indexedId <- oldSeq
        if indexedId != item.id
      ) yield indexedId
    if (newSeq.isEmpty) reverseStore = reverseStore - getCore(item)
    else reverseStore = reverseStore + (getCore(item) -> newSeq)
  }

  def deleteFromStore(id: I, source: String) = {
    val old = store.get(id)
    deleteFromDeduplication(old)
    store = store - id
    old
  }

}
