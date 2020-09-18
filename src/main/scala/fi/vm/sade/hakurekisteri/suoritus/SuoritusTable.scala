package fi.vm.sade.hakurekisteri.suoritus

import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import org.joda.time.LocalDate
import java.util.UUID

import fi.vm.sade.hakurekisteri.rest.support.JournalTable
import java.sql.SQLDataException

import fi.vm.sade.hakurekisteri.suoritus.yksilollistaminen.Yksilollistetty

object SuoritusRowTypes {
  type SuoritusRow = (
    String,
    String,
    Option[Boolean],
    Option[String],
    Option[String],
    Option[LocalDate],
    Option[Yksilollistetty],
    Option[String],
    Option[String],
    Option[Int],
    Option[String],
    Option[Int],
    String,
    Map[String, String]
  )
}

import SuoritusRowTypes._

class SuoritusTable(tag: Tag) extends JournalTable[Suoritus, UUID, SuoritusRow](tag, "suoritus") {
  def myontaja = column[String]("myontaja")
  def henkiloOid = column[String]("henkilo_oid")
  def vahvistettu = column[Option[Boolean]]("vahvistettu")

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

  def lahdeArvot: Rep[Map[String, String]] =
    column[Map[String, String]]("lahde_arvot", O.SqlType("TEXT"))

  override def resourceShape = (
    myontaja,
    henkiloOid,
    vahvistettu,
    komo,
    tila,
    valmistuminen,
    yksilollistaminen,
    suoritusKieli,
    kuvaus,
    vuosi,
    tyyppi,
    index,
    source,
    lahdeArvot
  ).shaped

  override def row(s: Suoritus): Option[SuoritusRow] = s match {
    case o: VirallinenSuoritus =>
      Some(
        o.myontaja,
        o.henkiloOid,
        Some(o.vahvistettu),
        Some(o.komo),
        Some(o.tila),
        Some(o.valmistuminen),
        Some(o.yksilollistaminen),
        Some(o.suoritusKieli),
        None,
        None,
        o.suoritustyyppi,
        None,
        o.source,
        o.lahdeArvot
      )
    case s: VapaamuotoinenSuoritus =>
      Some(
        s.myontaja,
        s.henkiloOid,
        Some(s.vahvistettu),
        None,
        None,
        None,
        None,
        None,
        Some(s.kuvaus),
        Some(s.vuosi),
        Some(s.tyyppi),
        Some(s.index),
        s.source,
        Map.empty
      )

  }

  override val deletedValues: String => SuoritusRow = (lahde) =>
    ("", "", Some(true), None, None, None, None, None, None, None, None, None, lahde, Map())

  override val resource: SuoritusRow => Suoritus = {
    case (
          myontaja,
          henkiloOid,
          vahvistettu,
          Some(komo),
          Some(tila),
          Some(valmistuminen),
          Some(yks),
          Some(suoritusKieli),
          _,
          _,
          tyyppi,
          _,
          source,
          lahdeArvot
        ) =>
      VirallinenSuoritus(
        komo,
        myontaja,
        tila,
        valmistuminen,
        henkiloOid,
        yks,
        suoritusKieli,
        vahv = vahvistettu.getOrElse(true),
        lahde = source,
        suoritustyyppi = tyyppi,
        lahdeArvot = lahdeArvot
      )
    case (
          myontaja,
          henkiloOid,
          vahvistettu,
          _,
          _,
          _,
          _,
          _,
          Some(kuvaus),
          Some(vuosi),
          Some(tyyppi),
          Some(index),
          source,
          lahdeArvot
        ) =>
      VapaamuotoinenSuoritus(henkiloOid, kuvaus, myontaja, vuosi, tyyppi, index, source)
    case row => throw new InvalidSuoritusDataException(row)
  }

  case class InvalidSuoritusDataException(row: SuoritusRow)
      extends SQLDataException(s"invalid data in database ${row.toString()}")

  override val extractSource: SuoritusRow => String = _._13
}
