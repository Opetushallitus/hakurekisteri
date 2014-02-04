package fi.vm.sade.hakurekisteri.domain

import java.util.Date

object yksilollistaminen extends Enumeration {
  type Yksilollistetty = Value
  val Ei = Value("Ei")
  val Osittain = Value("Osittain")
  val Kokonaan = Value("Kokonaan")
  val Alueittain = Value("Alueittain")
}

import yksilollistaminen._

case class Komoto(oid: String, komo: String, tarjoaja: String)
case class Suoritus(komoto: Komoto, tila: String, luokkataso: String, valmistuminen: Date, luokka: String, henkiloOid: String, yksilollistaminen: Yksilollistetty)


object PerusopetuksenToteutus {
  def apply (oppilaitos: String) : Komoto = {
    new Komoto("komotoid", "peruskoulu", oppilaitos)
  }
}

object Peruskoulu {

  def apply(oppilaitos: String, tila: String, luokkataso: String, valmistuminen: Date, luokka: String, henkiloOid: String): Suoritus = {
    new Suoritus(PerusopetuksenToteutus(oppilaitos), tila, luokkataso, valmistuminen, luokka, henkiloOid, Ei)
  }

}

object OsittainYksilollistettyPerusopetus {

  def apply(oppilaitos: String, tila: String, luokkataso: String, valmistuminen: Date, luokka: String, henkiloOid: String): Suoritus = {
    new Suoritus(PerusopetuksenToteutus(oppilaitos), tila, luokkataso, valmistuminen, luokka, henkiloOid, Osittain)
  }

}

object AlueittainYksilollistettyPerusopetus {

  def apply(oppilaitos: String, tila: String, luokkataso: String, valmistuminen: Date, luokka: String, henkiloOid: String): Suoritus = {
    new Suoritus(PerusopetuksenToteutus(oppilaitos), tila, luokkataso, valmistuminen, luokka, henkiloOid, Alueittain)
  }

}

object KokonaanYksillollistettyPerusopetus {

  def apply(oppilaitos: String, tila: String, luokkataso: String, valmistuminen: Date, luokka: String, henkiloOid: String): Suoritus = {
    new Suoritus(PerusopetuksenToteutus(oppilaitos), tila, luokkataso, valmistuminen, luokka, henkiloOid, Kokonaan)
  }

}
