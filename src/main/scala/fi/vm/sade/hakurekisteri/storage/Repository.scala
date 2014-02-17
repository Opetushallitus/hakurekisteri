package fi.vm.sade.hakurekisteri.storage

import java.util.UUID

import scala.slick.lifted.AbstractTable
import scala.slick.lifted

trait Repository[T] {

  def save(t:T):T with Identified

  def listAll():Seq[T with Identified]

}

trait JournaledRepository[T] extends InMemRepository[T] {

  private[this] var _journal:Seq[T with Identified] = Seq()
  protected def journal:Seq[T with Identified] = _journal

  loadJournal()

  def loadJournal() {
    store = journal.map((f: T with Identified) => f.id -> f).toMap
  }

  def addModification(o: T with Identified) {
    _journal = _journal :+ o
  }

  override def saveIdentified(o: T with Identified): T with Identified  = {
    addModification(o)
    super.saveIdentified(o)
  }


}


trait InMemRepository[T] extends Repository[T] {

  var store:Map[UUID,T with Identified] = Map()

  def save(o: T ): T with Identified = {
    val oid = identify(o)
    saveIdentified(oid)
  }

  protected def saveIdentified(oid: T with Identified) = {
    store = store + (oid.id -> oid)
    oid
  }

  def identify(o: T): T with Identified

  def listAll(): Seq[T with Identified] = {
    store.values.toSeq
  }


}


import scala.slick.driver.JdbcDriver.simple._


trait SlickRepository[T,P <: AbstractTable[_], R] extends JournaledRepository[T] {

  val db: Database
  val table: scala.slick.lifted.TableQuery[P]

  def toRow(resource: T with Identified):  P#TableElementType
  def toResource(row: P#TableElementType): T with Identified


  override def addModification(o: T with Identified) {
    db withSession {
      implicit session =>
      table += toRow(o)
    }
  }


  override def loadJournal() {
    db withSession {
      implicit session =>
        val foo = table.list.map(toResource)
        store = foo.map((f) => f.id -> f).toMap
    }

  }

}
