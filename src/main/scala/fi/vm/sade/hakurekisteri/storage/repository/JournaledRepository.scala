package fi.vm.sade.hakurekisteri.storage.repository

import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.rest.support.Resource


trait JournaledRepository[T <: Resource[I], I] extends InMemRepository[T, I] {

  val deduplicate = true

  val journal:Journal[T, I]

  var snapShot= store
  var reverseSnapShot = reverseStore

  loadJournal()

  def indexSwapSnapshot():Unit = {}


  def loadDelta(delta: Delta[T, I]) = delta match {
    case Updated(resource) =>
      val old = snapShot.get(resource.id)
      for (deleted <- old) {

        val newSeq = reverseSnapShot.get(deleted).map(_.filter(_ != resource.id)).getOrElse(Set())
        if (newSeq.isEmpty) reverseSnapShot = reverseSnapShot - deleted
        else reverseSnapShot = reverseSnapShot + (deleted -> newSeq)

      }

      snapShot =  snapShot + (resource.id -> resource)
      val newSeq =  reverseSnapShot.get(resource).getOrElse(Set()) + resource.id
      reverseSnapShot = reverseSnapShot + (resource -> newSeq)
      index(old, Some(resource))
    case Deleted(id, source) =>
      val old = snapShot.get(id)
      for (deleted <- old) {

        val newSeq = reverseSnapShot.get(deleted).map(_.filter(_ != id)).getOrElse(Set())
        if (newSeq.isEmpty) reverseSnapShot = reverseSnapShot - deleted
        else reverseSnapShot = reverseSnapShot + (deleted -> newSeq)

      }
      snapShot = snapShot - id
      index(old, None)
  }

  def loadJournal(time: Option[Long] = None) {
    for (
      delta <- journal.journal(time)
    ) loadDelta(delta)
    store = snapShot
    reverseStore = reverseSnapShot
    if (time.isEmpty && deduplicate)
      for (oids <- reverseSnapShot.values
           if oids.size > 1;
           duplicate <- oids.tail) delete(duplicate, source = "1.2.246.562.10.00000000001")
    indexSwapSnapshot()
  }

  override def saveIdentified(o: T with Identified[I]): T with Identified[I]  = {
    journal.addModification(Updated[T,I](o))
    super.saveIdentified(o)
  }

  override def deleteFromStore(id: I, source: String): Option[T with Identified[I]] = {
    journal.addModification(Deleted[T,I](id, source))
    super.deleteFromStore(id, source)
  }

}

trait Journal[T <: Resource[I], I] {

  def journal(latest:Option[Long]):Seq[Delta[T, I]]

  def addModification(o: Delta[T, I])

  var latestReload:Option[Long] = None

}

sealed abstract class Delta[T <: Resource[I], I]
case class Updated[T <: Resource[I], I](current:T with Identified[I]) extends Delta[T, I]
case class Deleted[T <: Resource[I], I](id:I, source: String) extends Delta[T, I]

class InMemJournal[T <: Resource[I], I] extends Journal[T, I] {

  protected var deltas: Seq[Delta[T, I]] = Seq()

  override def journal(latest:Option[Long]): Seq[Delta[T, I]] = deltas

  override def addModification(delta:Delta[T, I]): Unit =  {
    deltas = deltas :+ delta
  }
}

