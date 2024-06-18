package fi.vm.sade.hakurekisteri.arvosana

import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriDriver, JournalTable}
import java.util.UUID

import org.joda.time.LocalDate
import HakurekisteriDriver.api._

class ArvosanaTable(tag: Tag)
    extends JournalTable[
      Arvosana,
      UUID,
      (
        UUID,
        String,
        String,
        String,
        Option[String],
        Boolean,
        Option[Int],
        Option[String],
        String,
        Map[String, String],
        Option[Int]
      )
    ](tag, "arvosana") {
  def suoritus: Rep[UUID] = column[UUID]("suoritus")
  def arvosana: Rep[String] = column[String]("arvosana")
  def asteikko: Rep[String] = column[String]("asteikko")
  def aine: Rep[String] = column[String]("aine")
  def lisatieto: Rep[Option[String]] = column[Option[String]]("lisatieto")
  def valinnainen: Rep[Boolean] = column[Boolean]("valinnainen")
  def pisteet: Rep[Option[Int]] = column[Option[Int]]("pisteet")
  def myonnetty: Rep[Option[String]] = column[Option[String]]("myonnetty")
  def lahdeArvot: Rep[Map[String, String]] =
    column[Map[String, String]]("lahde_arvot", O.SqlType("TEXT"))
  def jarjestys: Rep[Option[Int]] = column[Option[Int]]("jarjestys")

  override def resourceShape = (
    suoritus,
    arvosana,
    asteikko,
    aine,
    lisatieto,
    valinnainen,
    pisteet,
    myonnetty,
    source,
    lahdeArvot,
    jarjestys
  ).shaped

  override def row(a: Arvosana): Option[
    (
      UUID,
      String,
      String,
      String,
      Option[String],
      Boolean,
      Option[Int],
      Option[String],
      String,
      Map[String, String],
      Option[Int]
    )
  ] = a.arvio match {
    case Arvio410(arvosana) =>
      Some(
        a.suoritus,
        arvosana,
        Arvio.ASTEIKKO_4_10,
        a.aine,
        a.lisatieto,
        a.valinnainen,
        None,
        a.myonnetty.map(_.toString),
        a.source,
        a.lahdeArvot,
        a.jarjestys
      )

    case ArvioYo(arvosana, pisteet) =>
      Some(
        a.suoritus,
        arvosana,
        Arvio.ASTEIKKOYO,
        a.aine,
        a.lisatieto,
        a.valinnainen,
        pisteet,
        a.myonnetty.map(_.toString),
        a.source,
        a.lahdeArvot,
        a.jarjestys
      )

    case ArvioOsakoe(pisteet: String) =>
      Some(
        a.suoritus,
        pisteet.toString,
        Arvio.ASTEIKKO_OSAKOE,
        a.aine,
        a.lisatieto,
        a.valinnainen,
        None,
        a.myonnetty.map(_.toString),
        a.source,
        a.lahdeArvot,
        a.jarjestys
      )

    case ArvioHyvaksytty(arvosana) =>
      Some(
        a.suoritus,
        arvosana,
        Arvio.ASTEIKKO_HYVAKSYTTY,
        a.aine,
        a.lisatieto,
        a.valinnainen,
        None,
        a.myonnetty.map(_.toString),
        a.source,
        a.lahdeArvot,
        a.jarjestys
      )
  }

  override val deletedValues: String => (
    UUID,
    String,
    String,
    String,
    Option[String],
    Boolean,
    Option[Int],
    Option[String],
    String,
    Map[String, String],
    Option[Int]
  ) =
    (lahde: String) =>
      (
        UUID.fromString("de1e7edd-e1e7-edde-1e7e-dde1e7ed1111"),
        "",
        "",
        "",
        None,
        false,
        None,
        None,
        lahde,
        Map(),
        None
      )
  override val resource: (
    (
      UUID,
      String,
      String,
      String,
      Option[String],
      Boolean,
      Option[Int],
      Option[String],
      String,
      Map[String, String],
      Option[Int]
    )
  ) => Arvosana =
    (arvosanaResource _).tupled

  def arvosanaResource(
    suoritus: UUID,
    arvosana: String,
    asteikko: String,
    aine: String,
    lisatieto: Option[String],
    valinnainen: Boolean,
    pisteet: Option[Int],
    myonnetty: Option[String],
    source: String,
    lahdeArvot: Map[String, String],
    jarjestys: Option[Int]
  ) =
    Arvosana(
      suoritus,
      Arvio(arvosana, asteikko, pisteet),
      aine,
      lisatieto,
      valinnainen,
      myonnetty = myonnetty.map(LocalDate.parse),
      source,
      lahdeArvot,
      jarjestys
    )

  override val extractSource: (
    (
      UUID,
      String,
      String,
      String,
      Option[String],
      Boolean,
      Option[Int],
      Option[String],
      String,
      Map[String, String],
      Option[Int]
    )
  ) => String = _._9
}
