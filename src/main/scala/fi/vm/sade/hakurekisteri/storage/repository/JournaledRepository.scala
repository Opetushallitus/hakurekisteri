package fi.vm.sade.hakurekisteri.storage.repository

import fi.vm.sade.hakurekisteri.storage.Identified
import scala.slick.lifted.{Ordered, AbstractTable}
import fi.vm.sade.hakurekisteri.rest.support.Resource
import java.util.UUID
import fi.vm.sade.hakurekisteri.arvosana.Arvosana


trait JournaledRepository[T <: Resource] extends InMemRepository[T] {

  val journal:Journal[T]

  loadJournal()

  def loadJournal() {
    store = journal.
      journal().
      foldLeft(Map():Map[UUID,T with Identified])((o,n) => n match {
        case Updated(resource) => o + (resource.id -> resource)
        case Deleted(id) => o - id
      })
  }

  override def saveIdentified(o: T with Identified): T with Identified  = {
    journal.addModification(Updated(o))
    super.saveIdentified(o)
  }

  override def deleteFromStore(id: UUID)  {
    journal.addModification(Deleted(id))
    super.deleteFromStore(id)
  }

}

trait Journal[T] {

  def journal():Seq[Delta[T]]

  def addModification(o: Delta[T])

}

sealed abstract class Delta[T]
case class Updated[T](current:T with Identified) extends Delta[T]
case class Deleted[T](id:UUID) extends Delta[T]

class InMemJournal[T] extends Journal[T] {

  private var deltas: Seq[Delta[T]] = Seq()

  override def journal(): Seq[Delta[T]] = deltas

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

  def update(resource:T with Identified): P#TableElementType

  def delete(id:UUID): P#TableElementType

  def toResource(row: P#TableElementType): T with Identified

  override def addModification(delta:Delta[T]) {
    db withSession {
      implicit session =>
        table += toRow(delta)
    }
  }

  def delta(row: P#TableElementType):Delta[T] = Updated(toResource(row))

  override def journal(): Seq[Delta[T]] = {
    db withSession {
      implicit session =>
        table.sortBy(journalSort).list.map(delta)
    }

  }
}
