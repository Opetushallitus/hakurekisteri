package fi.vm.sade.hakurekisteri.suoritus

import java.util.UUID
import fi.vm.sade.hakurekisteri.storage.Identified
import org.joda.time.{LocalDate, DateTime}
import fi.vm.sade.hakurekisteri.rest.support.Resource

object yksilollistaminen extends Enumeration {
  type Yksilollistetty = Value
  val Ei = Value("Ei")
  val Osittain = Value("Osittain")
  val Kokonaan = Value("Kokonaan")
  val Alueittain = Value("Alueittain")
}

import yksilollistaminen._

case class Komoto(oid: String, komo: String, tarjoaja: String)
case class Suoritus(komoto: Komoto, tila: String, valmistuminen: LocalDate, henkiloOid: String, yksilollistaminen: Yksilollistetty, suoritusKieli: String)  extends Resource{
  override def identify[R <: Suoritus](id: UUID): R with Identified =  Suoritus.identify(this,id).asInstanceOf[R with Identified]

}


object Suoritus {
  def identify(o:Suoritus): Suoritus with Identified = o match {
    case o: Suoritus with Identified => o
    case _ => o.identify(UUID.randomUUID())

  }

  def identify(o:Suoritus, id:UUID) = {
    new Suoritus(o.komoto: Komoto, o.tila: String, o.valmistuminen, o.henkiloOid: String, o.yksilollistaminen, o.suoritusKieli) with Identified{
      val id: UUID = UUID.randomUUID()
    }
  }
}

object PerusopetuksenToteutus {
  def apply (oppilaitos: String) : Komoto = {
    Komoto("komotoid", "peruskoulu", oppilaitos)
  }
}

object Peruskoulu {

  def apply(oppilaitos: String, tila: String, valmistuminen: LocalDate, henkiloOid: String): Suoritus = {
    Suoritus(PerusopetuksenToteutus(oppilaitos), tila, valmistuminen, henkiloOid, Ei, "fi")
  }

  def apply(oppilaitos: String, tila: String, valmistuminen: DateTime, henkiloOid: String): Suoritus = {
    Suoritus(PerusopetuksenToteutus(oppilaitos), tila, valmistuminen.toLocalDate, henkiloOid, Ei, "fi")
  }

}



object OsittainYksilollistettyPerusopetus {

  def apply(oppilaitos: String, tila: String, valmistuminen: LocalDate, henkiloOid: String): Suoritus = {
    Suoritus(PerusopetuksenToteutus(oppilaitos), tila, valmistuminen, henkiloOid, Osittain, "fi")
  }

}

object AlueittainYksilollistettyPerusopetus {

  def apply(oppilaitos: String, tila: String, valmistuminen: LocalDate, henkiloOid: String): Suoritus = {
    Suoritus(PerusopetuksenToteutus(oppilaitos), tila, valmistuminen, henkiloOid, Alueittain, "fi")
  }

}

object KokonaanYksillollistettyPerusopetus {

  def apply(oppilaitos: String, tila: String, valmistuminen: LocalDate, henkiloOid: String): Suoritus = {
    Suoritus(PerusopetuksenToteutus(oppilaitos), tila, valmistuminen, henkiloOid, Kokonaan, "fi")
  }

}
