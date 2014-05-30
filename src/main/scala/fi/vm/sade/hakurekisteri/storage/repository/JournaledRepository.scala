package fi.vm.sade.hakurekisteri.storage.repository

import fi.vm.sade.hakurekisteri.storage.Identified
import scala.slick.lifted.{ColumnOrdered, Ordered, AbstractTable}
import fi.vm.sade.hakurekisteri.rest.support.Resource
import java.util.UUID
import scala.slick.lifted


trait JournaledRepository[T <: Resource] extends InMemRepository[T] {

  val journal:Journal[T]

  var snapShot= store
  var reverseSnapShot = reverseStore

  loadJournal()

  def indexSwapSnapshot():Unit = {}


  def loadDelta(delta: Delta[T]) = delta match {
    case Updated(resource) =>
      val old = snapShot.get(resource.id)
      for (deleted <- old) {

        val newSeq = reverseSnapShot.get(deleted).map(_.filter(_ != resource.id)).getOrElse(Seq())
        if (newSeq.isEmpty) reverseSnapShot = reverseSnapShot - deleted
        else reverseSnapShot = reverseSnapShot + (deleted -> newSeq)

      }

      snapShot =  snapShot + (resource.id -> resource)
      val newSeq = resource.id +: reverseSnapShot.get(resource).getOrElse(Seq())
      reverseSnapShot = reverseSnapShot + (resource -> newSeq)
      index(old, Some(resource))
    case Deleted(id) =>
      val old = snapShot.get(id)
      for (deleted <- old) {

        val newSeq = reverseSnapShot.get(deleted).map(_.filter(_ != id)).getOrElse(Seq())
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
    if (time.isEmpty)
      for (oids <- reverseSnapShot.values
           if oids.length > 1;
           duplicate <- oids.tail) delete(duplicate)
    indexSwapSnapshot()
  }

  override def saveIdentified(o: T with Identified): T with Identified  = {
    journal.addModification(Updated(o))
    super.saveIdentified(o)
  }

  override def deleteFromStore(id: UUID): Option[T with Identified] = {
    journal.addModification(Deleted(id))
    super.deleteFromStore(id)
  }

}

trait Journal[T] {

  def journal(latest:Option[Long]):Seq[Delta[T]]

  def addModification(o: Delta[T])

  var latestReload:Option[Long] = None

}

sealed abstract class Delta[T]
case class Updated[T](current:T with Identified) extends Delta[T]
case class Deleted[T](id:UUID) extends Delta[T]

class InMemJournal[T] extends Journal[T] {

  private var deltas: Seq[Delta[T]] = Seq()

  override def journal(latest:Option[Long]): Seq[Delta[T]] = deltas

  override def addModification(delta:Delta[T]): Unit =  {
    deltas = deltas :+ delta
  }
}

import scala.slick.driver.JdbcDriver.simple._

trait JDBCJournal[T, P <: AbstractTable[_], O <: Ordered] extends Journal[T] {
  val db: Database
  val table: scala.slick.lifted.TableQuery[P]
  val journalSort: P => O

  private[this] def toRow(delta:Delta[T]): P#TableElementType = delta match {
    case Updated(resource) => update(resource)
    case Deleted(id) => delete(id)
  }


  def currentState(id: UUID): P#TableElementType  = {
    db withSession(
      implicit session =>
        table.filter(filterByResourceId(id)).sortBy(newest).take(1).list().head)
  }

  def newest: (P) => ColumnOrdered[Long]

  def filterByResourceId(id: UUID): (P) => Column[Boolean]

  def update(resource:T with Identified): P#TableElementType

  def delete(id:UUID): P#TableElementType

  //def toResource(row: P#TableElementType): T with Identified

  override def addModification(delta:Delta[T]) {
    db withSession {
      implicit session =>
        table += toRow(delta)
    }
  }

  def delta(row: P#TableElementType):Delta[T]

  def timestamp(resource: P): lifted.Column[Long]
  def timestamp(resource: P#TableElementType): Long


  def loadFromDb(latest:Option[Long]): List[P#TableElementType] = latest match  {
    case None =>
      db withSession {
        implicit session =>
          table.sortBy(journalSort).list
      }
    case Some(latest) =>

      db withSession {
        implicit session =>
          table.filter(timestamp(_) >= latest).sortBy(journalSort).list
      }


  }



  override def journal(latest:Option[Long]): Seq[Delta[T]] = {
    val dbList: List[P#TableElementType] = loadFromDb(latest)

    latestReload = dbList.lastOption.map(timestamp(_))
    dbList.map(delta)
  }
}
