package fi.vm.sade.hakurekisteri.arvosana

import scala.slick.driver.JdbcDriver.simple._
import java.util.UUID
import org.joda.time.LocalDate
import fi.vm.sade.hakurekisteri.rest.support.JournalTable


class ArvosanaTable(tag: Tag) extends JournalTable[Arvosana, UUID, (String, String, String, String, Option[String], Boolean, Option[Int], Option[String], String)](tag, "arvosana") {

  def suoritus = column[String]("suoritus")
  def arvosana = column[String]("arvosana")
  def asteikko = column[String]("asteikko")
  def aine = column[String]("aine")
  def lisatieto = column[Option[String]]("lisatieto")
  def valinnainen = column[Boolean]("valinnainen")
  def pisteet = column[Option[Int]]("pisteet")
  def myonnetty = column[Option[String]]("myonnetty")

  override def resourceShape = (suoritus, arvosana, asteikko, aine, lisatieto, valinnainen, pisteet, myonnetty, source).shaped

  override def row(a: Arvosana): Option[(String, String, String, String, Option[String], Boolean, Option[Int], Option[String], String)] = a.arvio match {
    case Arvio410(arvosana) => Some(a.suoritus.toString, arvosana, Arvio.ASTEIKKO_4_10, a.aine, a.lisatieto, a.valinnainen, None, a.myonnetty.map(_.toString), a.source)
    case ArvioYo(arvosana, pisteet) =>  Some(a.suoritus.toString, arvosana, Arvio.ASTEIKKOYO, a.aine, a.lisatieto, a.valinnainen, pisteet , a.myonnetty.map(_.toString), a.source)
  }

  override val deletedValues: (String, String, String, String, Option[String], Boolean, Option[Int], Option[String], String) = ("", "","", "", None, false, None, None, "")
  override val resource: ((String, String, String, String, Option[String], Boolean, Option[Int], Option[String], String)) => Arvosana = (arvosanaResource _).tupled

  def arvosanaResource(suoritus: String, arvosana: String, asteikko: String, aine: String, lisatieto: Option[String], valinnainen: Boolean, pisteet: Option[Int], myonnetty: Option[String], source: String) =
    Arvosana(UUID.fromString(suoritus), Arvio(arvosana, asteikko, pisteet), aine, lisatieto, valinnainen, myonnetty = myonnetty.map(LocalDate.parse), source)

  override def getId(serialized: String): UUID = UUID.fromString(serialized)
}