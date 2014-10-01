package fi.vm.sade.hakurekisteri.opiskelija

import org.joda.time.DateTime
import scala.slick.driver.JdbcDriver.simple._
import java.util.UUID
import fi.vm.sade.hakurekisteri.rest.support.{JournalTable, HakurekisteriColumns}





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

