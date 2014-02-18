package fi.vm.sade.hakurekisteri.storage.repository

import fi.vm.sade.hakurekisteri.storage.Identified
import scala.slick.lifted.{Ordered, AbstractTable}
import fi.vm.sade.hakurekisteri.rest.support.Resource


trait JournaledRepository[T <: Resource] extends InMemRepository[T] {

  val journal:Journal[T]

  loadJournal()

  def loadJournal() {
    store = journal.journal().map((f: T with Identified) => f.id -> f).toMap
  }

  override def saveIdentified(o: T with Identified): T with Identified  = {
    journal.addModification(o)
    super.saveIdentified(o)
  }


}

trait Journal[T] {

  def journal():Seq[T with Identified]

  def addModification(o: T with Identified)

}

class InMemJournal[T] extends Journal[T] {

  private var deltas: Seq[T with Identified] = Seq()

  override def journal(): Seq[T with Identified] = deltas

  override def addModification(o: T with Identified): Unit = deltas = deltas :+ o
}

import scala.slick.driver.JdbcDriver.simple._

trait JDBCJournal[T, P <: AbstractTable[_], O <: Ordered] extends Journal[T] {
  val db: Database
  val table: scala.slick.lifted.TableQuery[P]
  val journalSort: P => O

  def toRow(resource: T with Identified):  P#TableElementType
  def toResource(row: P#TableElementType): T with Identified

  def addModification(o: T with Identified) {
    db withSession {
      implicit session =>
        table += toRow(o)
    }
  }


  override def journal(): Seq[T with Identified] = {
    db withSession {
      implicit session =>
        table.sortBy(journalSort).list.map(toResource)
    }

  }
}
