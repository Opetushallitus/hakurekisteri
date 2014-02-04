package fi.vm.sade.hakurekisteri.suoritus

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
case class Suoritus(komoto: Komoto, tila: String, valmistuminen: Date, henkiloOid: String, yksilollistaminen: Yksilollistetty)


object PerusopetuksenToteutus {
  def apply (oppilaitos: String) : Komoto = {
    Komoto("komotoid", "peruskoulu", oppilaitos)
  }
}

object Peruskoulu {

  def apply(oppilaitos: String, tila: String, valmistuminen: Date, henkiloOid: String): Suoritus = {
    Suoritus(PerusopetuksenToteutus(oppilaitos), tila, valmistuminen, henkiloOid, Ei)
  }

}

object OsittainYksilollistettyPerusopetus {

  def apply(oppilaitos: String, tila: String, valmistuminen: Date, henkiloOid: String): Suoritus = {
    Suoritus(PerusopetuksenToteutus(oppilaitos), tila, valmistuminen, henkiloOid, Osittain)
  }

}

object AlueittainYksilollistettyPerusopetus {

  def apply(oppilaitos: String, tila: String, valmistuminen: Date, henkiloOid: String): Suoritus = {
    Suoritus(PerusopetuksenToteutus(oppilaitos), tila, valmistuminen, henkiloOid, Alueittain)
  }

}

object KokonaanYksillollistettyPerusopetus {

  def apply(oppilaitos: String, tila: String, valmistuminen: Date, henkiloOid: String): Suoritus = {
    Suoritus(PerusopetuksenToteutus(oppilaitos), tila, valmistuminen, henkiloOid, Kokonaan)
  }

}
