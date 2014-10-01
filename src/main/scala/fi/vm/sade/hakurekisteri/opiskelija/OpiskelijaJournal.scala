package fi.vm.sade.hakurekisteri.opiskelija

import org.joda.time.DateTime
import scala.slick.driver.JdbcDriver.simple._
import java.util.UUID
import scala.slick.jdbc.meta.MTable
import scala.slick.lifted.TableQuery
import fi.vm.sade.hakurekisteri.rest.support.{JournalTable, JDBCJournal, HakurekisteriColumns}


class OpiskelijaJournal(override val db: Database) extends JDBCJournal[Opiskelija, UUID, OpiskelijaTable](TableQuery[OpiskelijaTable]) {
  db withSession(
    implicit session =>
      if (MTable.getTables("opiskelija").list().isEmpty) {
        table.ddl.create
      }
  )

}



import HakurekisteriColumns._

class OpiskelijaTable(tag: Tag) extends JournalTable[Opiskelija, UUID, (String, String, String, String, DateTime, Option[DateTime], String)](tag, "opiskelija") {
  def oppilaitosOid = column[String]("oppilaitos_oid")
  def luokkataso = column[String]("luokkataso")
  def luokka = column[String]("luokka")
  def henkiloOid = column[String]("henkilo_oid")
  def alkuPaiva = column[DateTime]("alku_paiva")
  def loppuPaiva = column[Option[DateTime]]("loppu_paiva")

  val deletedValues = ("", "", "", "", DateTime.now(), None, "")

  override def resourceShape = (oppilaitosOid, luokkataso, luokka, henkiloOid, alkuPaiva, loppuPaiva, source).shaped

  override def row(o: Opiskelija): Option[(String, String, String, String, DateTime, Option[DateTime], String)] = Opiskelija.unapply(o)

  override def getId(serialized: String): UUID = UUID.fromString(serialized)

  override val resource = (Opiskelija.apply _).tupled

}

