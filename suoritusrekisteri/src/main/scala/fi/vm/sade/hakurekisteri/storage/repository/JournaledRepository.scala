package fi.vm.sade.hakurekisteri.storage.repository

import fi.vm.sade.hakurekisteri.Oids
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.rest.support.Resource

trait JournaledRepository[T <: Resource[I, T], I] extends InMemRepository[T, I] {

  import fi.vm.sade.hakurekisteri.Config

  val deduplicate = true

  val journal: Journal[T, I]

  var snapShot = store
  var reverseSnapShot = reverseStore

  loadJournal()

  def indexSwapSnapshot(): Unit = {}

  def loadDelta(delta: Delta[T, I]) = delta match {
    case Updated(resource) =>
      val old = snapShot.get(resource.id)
      for (deleted <- old) {
        val deleteCore = getCore(deleted)
        val newSeq =
          reverseSnapShot.get(deleteCore).map(_.filter(_ != resource.id)).getOrElse(List())
        if (newSeq.isEmpty) reverseSnapShot = reverseSnapShot - deleteCore
        else reverseSnapShot = reverseSnapShot + (deleteCore -> newSeq)
      }
      val core = getCore(resource)
      snapShot = snapShot + (resource.id -> resource)
      val newSeq = reverseSnapShot.getOrElse(core, List()).+:(resource.id)
      reverseSnapShot = reverseSnapShot + (core -> newSeq)
      index(old, Some(resource))

    case Deleted(id, source) =>
      val old = snapShot.get(id)
      for (deleted <- old) {
        val deleteCore = getCore(deleted)
        val newSeq = reverseSnapShot.get(deleteCore).map(_.filter(_ != id)).getOrElse(List())
        if (newSeq.isEmpty) reverseSnapShot = reverseSnapShot - deleteCore
        else reverseSnapShot = reverseSnapShot + (deleteCore -> newSeq)
      }
      snapShot = snapShot - id
      index(old, None)

    case Insert(_) =>
      throw new NotImplementedError("Insert deltas not implemented in JournaledRepository")
  }

  def loadJournal(time: Option[Long] = None) {
    for (delta <- journal.journal(time)) loadDelta(delta)
    store = snapShot
    reverseStore = reverseSnapShot
    if (time.isEmpty && deduplicate)
      for (
        oids <- reverseSnapShot.values
        if oids.size > 1;
        duplicate <- oids.tail.toSet[I]
      ) delete(duplicate, source = Oids.ophOrganisaatioOid)
    indexSwapSnapshot()
  }

  override def saveIdentified(o: T with Identified[I]): T with Identified[I] = {
    journal.addModification(Updated[T, I](o))
    super.saveIdentified(o)
  }

  override def deleteFromStore(id: I, source: String): Option[T with Identified[I]] = {
    journal.addModification(Deleted[T, I](id, source))
    super.deleteFromStore(id, source)
  }

}

trait Journal[T <: Resource[I, T], I] {

  def journal(latest: Option[Long]): Seq[Delta[T, I]]

  def addModification(o: Delta[T, I])

  var latestReload: Option[Long] = None

}

sealed abstract class Delta[T <: Resource[I, T], I] {
  def id: I
}
case class Updated[T <: Resource[I, T], I](current: T with Identified[I]) extends Delta[T, I] {
  override val id = current.id
}
case class Insert[T <: Resource[I, T], I](current: T with Identified[I]) extends Delta[T, I] {
  override val id = current.id
}
case class Deleted[T <: Resource[I, T], I](id: I, source: String) extends Delta[T, I]

class InMemJournal[T <: Resource[I, T], I] extends Journal[T, I] {

  protected var deltas: Seq[Delta[T, I]] = Seq()

  override def journal(latest: Option[Long]): Seq[Delta[T, I]] = deltas

  override def addModification(delta: Delta[T, I]): Unit = deltas = deltas :+ delta
}
