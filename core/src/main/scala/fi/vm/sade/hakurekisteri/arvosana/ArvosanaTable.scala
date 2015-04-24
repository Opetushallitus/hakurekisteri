package fi.vm.sade.hakurekisteri.arvosana

import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriDriver, JournalTable}
import HakurekisteriDriver.simple._
import java.util.UUID
import org.joda.time.LocalDate
import scala.util.Try


class ArvosanaTable(tag: Tag) extends JournalTable[Arvosana, UUID, (UUID, Option[String], String, String, String, Option[String], Option[String], Boolean, Option[Int], Option[String], String, Option[Int])](tag, "arvosana") {

  def suoritus = column[UUID]("suoritus")
  def koetunnus = column[Option[String]]("koetunnus")
  def arvosana = column[String]("arvosana")
  def asteikko = column[String]("asteikko")
  def aine = column[String]("aine")
  def aineyhdistelmarooli = column[Option[String]]("aineyhdistelmarooli")
  def lisatieto = column[Option[String]]("lisatieto")
  def valinnainen = column[Boolean]("valinnainen")
  def pisteet = column[Option[Int]]("pisteet")
  def myonnetty = column[Option[String]]("myonnetty")
  def jarjestys = column[Option[Int]]("jarjestys")

  override def resourceShape = (suoritus, koetunnus, arvosana, asteikko, aine, aineyhdistelmarooli, lisatieto, valinnainen, pisteet, myonnetty, source, jarjestys).shaped

  override def row(a: Arvosana): Option[(UUID, Option[String], String, String, String, Option[String], Option[String], Boolean, Option[Int], Option[String], String, Option[Int])] = a.arvio match {
    case Arvio410(arvosana) =>
      Some(a.suoritus, a.koetunnus, arvosana, Arvio.ASTEIKKO_4_10, a.aine, a.aineyhdistelmarooli, a.lisatieto, a.valinnainen, None, a.myonnetty.map(_.toString), a.source, a.jarjestys)

    case ArvioYo(arvosana, pisteet) =>
      Some(a.suoritus, a.koetunnus, arvosana, Arvio.ASTEIKKOYO, a.aine, a.aineyhdistelmarooli, a.lisatieto, a.valinnainen, pisteet, a.myonnetty.map(_.toString), a.source, a.jarjestys)

    case ArvioOsakoe(pisteet: String) =>
      Some(a.suoritus, a.koetunnus, pisteet.toString, Arvio.ASTEIKKO_OSAKOE, a.aine, a.aineyhdistelmarooli, a.lisatieto, a.valinnainen, None, a.myonnetty.map(_.toString), a.source, a.jarjestys)
  }

  override val deletedValues: (String) => (UUID, Option[String], String, String, String, Option[String], Option[String], Boolean, Option[Int], Option[String], String, Option[Int]) =
    (lahde: String) => (UUID.fromString("de1e7edd-e1e7-edde-1e7e-dde1e7ed1111"), None, "","", "", None, None, false, None, None, lahde, None)
  override val resource: ((UUID, Option[String], String, String, String, Option[String], Option[String], Boolean, Option[Int], Option[String], String, Option[Int])) => Arvosana =
    (arvosanaResource _).tupled

  def arvosanaResource(suoritus: UUID, koetunnus: Option[String], arvosana: String, asteikko: String, aine: String, aineyhdistelmarooli: Option[String], lisatieto: Option[String], valinnainen: Boolean, pisteet: Option[Int], myonnetty: Option[String], source: String, jarjestys: Option[Int]) =
    Arvosana(suoritus, koetunnus, Arvio(arvosana, asteikko, pisteet), aine, aineyhdistelmarooli, lisatieto, valinnainen, myonnetty = myonnetty.map(LocalDate.parse), source, jarjestys)

  override val extractSource: ((UUID, Option[String], String, String, String, Option[String], Option[String], Boolean, Option[Int], Option[String], String, Option[Int])) => String = _._11
}