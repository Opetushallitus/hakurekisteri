package fi.vm.sade.hakurekisteri.opiskelija

import org.joda.time.DateTime
import java.util.UUID

import fi.vm.sade.hakurekisteri.rest.support.JournalTable
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import slick.lifted.ShapedValue

class OpiskelijaTable(tag: Tag)
    extends JournalTable[
      Opiskelija,
      UUID,
      (String, String, String, String, DateTime, Option[DateTime], String)
    ](tag, "opiskelija") {

  def oppilaitosOid: Rep[String] = column[String]("oppilaitos_oid")
  def luokkataso: Rep[String] = column[String]("luokkataso")
  def luokka: Rep[String] = column[String]("luokka")
  def henkiloOid: Rep[String] = column[String]("henkilo_oid")
  def alkuPaiva: Rep[DateTime] = column[DateTime]("alku_paiva")
  def loppuPaiva: Rep[Option[DateTime]] = column[Option[DateTime]]("loppu_paiva")

  val deletedValues = (lahde: String) => ("", "", "", "", DateTime.now(), None, lahde)

  override def resourceShape =
    (oppilaitosOid, luokkataso, luokka, henkiloOid, alkuPaiva, loppuPaiva, source).shaped

  override def row(
    o: Opiskelija
  ): Option[(String, String, String, String, DateTime, Option[DateTime], String)] =
    Opiskelija.unapply(o)

  override val resource = (Opiskelija.apply _).tupled
  override val extractSource
    : ((String, String, String, String, DateTime, Option[DateTime], String)) => String = _._7
}
