package fi.vm.sade.hakurekisteri.suoritus

import scala.slick.driver.JdbcDriver.simple._
import org.joda.time.LocalDate
import java.util.UUID
import fi.vm.sade.hakurekisteri.rest.support.JournalTable
import fi.vm.sade.hakurekisteri.suoritus.{yksilollistaminen => yksil}
import java.sql.SQLDataException


object SuoritusRowTypes {
  type SuoritusRow = (String, String, Option[String], Option[String], Option[String], Option[String], Option[String], Option[String], Option[Int], Option[String], String)
}

import SuoritusRowTypes._

class SuoritusTable(tag: Tag) extends JournalTable[Suoritus, UUID, SuoritusRow](tag, "suoritus") {



  def myontaja = column[String]("myontaja")
  def henkiloOid = column[String]("henkilo_oid")

  //virallinen
  def komo = column[Option[String]]("komo")
  def tila = column[Option[String]]("tila")
  def valmistuminen = column[Option[String]]("valmistuminen")
  def yksilollistaminen = column[Option[String]]("yksilollistaminen")
  def suoritusKieli = column[Option[String]]("suoritus_kieli")

  //vapaamuotoinen
  def kuvaus = column[Option[String]]("kuvaus")
  def vuosi = column[Option[Int]]("vuosi")
  def tyyppi = column[Option[String]]("tyyppi")

  override def resourceShape = (myontaja, henkiloOid, komo, tila, valmistuminen, yksilollistaminen, suoritusKieli, kuvaus, vuosi, tyyppi, source).shaped

  override def row(s: Suoritus): Option[SuoritusRow] = s match {
    case o: VirallinenSuoritus =>
      Some( o.myontaja, o.henkiloOid, Some(o.komo),Some(o.tila), Some(o.valmistuminen.toString), Some(o.yksilollistaminen.toString), Some(o.suoritusKieli), None, None, None, o.source)
    case s: VapaamuotoinenSuoritus =>
      Some(s.myontaja, s.henkiloOid, None, None, None,  None, None, Some(s.kuvaus), Some(s.vuosi), Some(s.tyyppi), s.source)

  }

  override val deletedValues = ("", "", None, None, None, None, None, None, None, None, "")

  override def getId(serialized: String): UUID = UUID.fromString(serialized)

  override val resource: (SuoritusRow) => Suoritus = {
    case (myontaja, henkiloOid, Some(komo), Some(tila), Some(valmistuminen), Some(yks), Some(suoritusKieli), _, _, _, source) =>
      VirallinenSuoritus(komo, myontaja, tila, LocalDate.parse(valmistuminen), henkiloOid, yksil.withName(yks), suoritusKieli, lahde = source)
    case (myontaja, henkiloOid, _, _, _, _, _, Some(kuvaus), Some(vuosi), Some(tyyppi), source)  =>
      VapaamuotoinenSuoritus(henkiloOid,kuvaus, myontaja, vuosi, tyyppi, source)
    case row => throw new InvalidSuoritusDataException(row)
  }


  case class InvalidSuoritusDataException(row: SuoritusRow) extends SQLDataException(s"invalid data in database ${row.toString()}")
}

