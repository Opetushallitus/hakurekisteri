package fi.vm.sade.hakurekisteri.suoritus

import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.simple._
import org.joda.time.LocalDate
import java.util.UUID
import fi.vm.sade.hakurekisteri.rest.support.JournalTable
import java.sql.SQLDataException
import fi.vm.sade.hakurekisteri.suoritus.yksilollistaminen.Yksilollistetty


object SuoritusRowTypes {
  type SuoritusRow = (String, String, Option[String], Option[String], Option[LocalDate], Option[Yksilollistetty], Option[String], Option[String], Option[Int], Option[String], Option[Int], String)
}

import SuoritusRowTypes._


class SuoritusTable(tag: Tag) extends JournalTable[Suoritus, UUID, SuoritusRow](tag, "suoritus") {



  def myontaja = column[String]("myontaja")
  def henkiloOid = column[String]("henkilo_oid")

  //virallinen
  def komo = column[Option[String]]("komo")
  def tila = column[Option[String]]("tila")
  def valmistuminen = column[Option[LocalDate]]("valmistuminen")
  def yksilollistaminen = column[Option[Yksilollistetty]]("yksilollistaminen")
  def suoritusKieli = column[Option[String]]("suoritus_kieli")

  //vapaamuotoinen
  def kuvaus = column[Option[String]]("kuvaus")
  def vuosi = column[Option[Int]]("vuosi")
  def tyyppi = column[Option[String]]("tyyppi")
  def index = column[Option[Int]]("index")

  override def resourceShape = (myontaja, henkiloOid, komo, tila, valmistuminen, yksilollistaminen, suoritusKieli, kuvaus, vuosi, tyyppi, index, source).shaped

  override def row(s: Suoritus): Option[SuoritusRow] = s match {
    case o: VirallinenSuoritus =>
      Some( o.myontaja, o.henkiloOid, Some(o.komo),Some(o.tila), Some(o.valmistuminen), Some(o.yksilollistaminen), Some(o.suoritusKieli), None, None, None, None, o.source)
    case s: VapaamuotoinenSuoritus =>
      Some(s.myontaja, s.henkiloOid, None, None, None,  None, None, Some(s.kuvaus), Some(s.vuosi), Some(s.tyyppi), Some(s.index), s.source)

  }

  override val deletedValues =(lahde: String) =>  ("", "", None, None, None, None, None, None, None, None, None, lahde)

  override val resource: (SuoritusRow) => Suoritus = {
    case (myontaja, henkiloOid, Some(komo), Some(tila), Some(valmistuminen), Some(yks), Some(suoritusKieli), _, _, _, _,  source) =>
      VirallinenSuoritus(komo, myontaja, tila, valmistuminen, henkiloOid, yks, suoritusKieli, lahde = source)
    case (myontaja, henkiloOid, _, _, _, _, _, Some(kuvaus), Some(vuosi), Some(tyyppi), Some(index), source)  =>
      VapaamuotoinenSuoritus(henkiloOid,kuvaus, myontaja, vuosi, tyyppi, index, source)
    case row => throw new InvalidSuoritusDataException(row)
  }


  case class InvalidSuoritusDataException(row: SuoritusRow) extends SQLDataException(s"invalid data in database ${row.toString()}")
}

