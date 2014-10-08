package fi.vm.sade.hakurekisteri.arvosana

import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriDriver, JournalTable}
import HakurekisteriDriver.simple._
import java.util.UUID
import org.joda.time.LocalDate
import scala.util.Try


class ArvosanaTable(tag: Tag) extends JournalTable[Arvosana, UUID, (UUID, String, String, String, Option[String], Boolean, Option[Int], Option[String], String)](tag, "arvosana") {

  def suoritus = column[UUID]("suoritus")
  def arvosana = column[String]("arvosana")
  def asteikko = column[String]("asteikko")
  def aine = column[String]("aine")
  def lisatieto = column[Option[String]]("lisatieto")
  def valinnainen = column[Boolean]("valinnainen")
  def pisteet = column[Option[Int]]("pisteet")
  def myonnetty = column[Option[String]]("myonnetty")

  override def resourceShape = (suoritus, arvosana, asteikko, aine, lisatieto, valinnainen, pisteet, myonnetty, source).shaped

  override def row(a: Arvosana): Option[(UUID, String, String, String, Option[String], Boolean, Option[Int], Option[String], String)] = a.arvio match {
    case Arvio410(arvosana) => Some(a.suoritus, arvosana, Arvio.ASTEIKKO_4_10, a.aine, a.lisatieto, a.valinnainen, None, a.myonnetty.map(_.toString), a.source)
    case ArvioYo(arvosana, pisteet) =>  Some(a.suoritus, arvosana, Arvio.ASTEIKKOYO, a.aine, a.lisatieto, a.valinnainen, pisteet , a.myonnetty.map(_.toString), a.source)
  }

  override val deletedValues: (String) => (UUID, String, String, String, Option[String], Boolean, Option[Int], Option[String], String) = (lahde: String) => (UUID.fromString("de1e7edd-e1e7-edde-1e7e-dde1e7ed1111"), "","", "", None, false, None, None, lahde)
  override val resource: ((UUID, String, String, String, Option[String], Boolean, Option[Int], Option[String], String)) => Arvosana = (arvosanaResource _).tupled

  def arvosanaResource(suoritus: UUID, arvosana: String, asteikko: String, aine: String, lisatieto: Option[String], valinnainen: Boolean, pisteet: Option[Int], myonnetty: Option[String], source: String) = {
    val arvio =  Try(Arvio(arvosana, asteikko, pisteet)).recover{
      case e:IllegalArgumentException => NA
      case e:UnknownScaleException  => NA

    }.get
    Arvosana(suoritus,arvio, aine, lisatieto, valinnainen, myonnetty = myonnetty.map(LocalDate.parse), source)
  }


}