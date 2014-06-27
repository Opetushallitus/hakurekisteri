package fi.vm.sade.hakurekisteri.suoritus

import java.util.UUID
import fi.vm.sade.hakurekisteri.storage.Identified
import org.joda.time.LocalDate
import fi.vm.sade.hakurekisteri.rest.support.{Kausi, Resource}
import fi.vm.sade.hakurekisteri.rest.support.Kausi.Kausi

object yksilollistaminen extends Enumeration {
  type Yksilollistetty = Value
  val Ei = Value("Ei")
  val Osittain = Value("Osittain")
  val Kokonaan = Value("Kokonaan")
  val Alueittain = Value("Alueittain")
}

import yksilollistaminen._

case class Komoto(oid: String, komo: String, tarjoaja: String, alkamisvuosi: String, alkamiskausi: Kausi)

case class Suoritus(komo: String, myontaja: String, tila: String, valmistuminen: LocalDate, henkiloOid: String, yksilollistaminen: Yksilollistetty, suoritusKieli: String) extends Resource[UUID] {
  override def identify(id: UUID): this.type with Identified[UUID] =  Suoritus.identify(this,id).asInstanceOf[this.type with Identified[UUID]]
}

object Suoritus {
  def identify(o:Suoritus): Suoritus with Identified[UUID] = o match {
    case o: Suoritus with Identified[UUID] => o
    case _ => o.identify(UUID.randomUUID)
  }

  def identify(o:Suoritus, identity:UUID) = {
    new Suoritus(o.komo: String, o.myontaja: String, o.tila: String, o.valmistuminen, o.henkiloOid: String, o.yksilollistaminen, o.suoritusKieli) with Identified[UUID] {
      val id: UUID = identity
    }
  }
}

object PerusopetuksenToteutus2005S {
  def apply (oppilaitos: String) : Komoto = {
    Komoto("komotoid", "peruskoulu", oppilaitos, "2005", Kausi.Syksy)
  }
}

object Peruskoulu {
  def apply(oppilaitos: String, tila: String, valmistuminen: LocalDate, henkiloOid: String): Suoritus = {
    Suoritus("peruskoulu", oppilaitos, tila, valmistuminen, henkiloOid, Ei, "fi")
  }
}

object OsittainYksilollistettyPerusopetus {
  def apply(oppilaitos: String, tila: String, valmistuminen: LocalDate, henkiloOid: String): Suoritus = {
    Suoritus("peruskoulu", oppilaitos, tila, valmistuminen, henkiloOid, Osittain, "fi")
  }
}

object AlueittainYksilollistettyPerusopetus {
  def apply(oppilaitos: String, tila: String, valmistuminen: LocalDate, henkiloOid: String): Suoritus = {
    Suoritus("peruskoulu", oppilaitos, tila, valmistuminen, henkiloOid, Alueittain, "fi")
  }
}

object KokonaanYksillollistettyPerusopetus {
  def apply(oppilaitos: String, tila: String, valmistuminen: LocalDate, henkiloOid: String): Suoritus = {
    Suoritus("peruskoulu", oppilaitos, tila, valmistuminen, henkiloOid, Kokonaan, "fi")
  }
}
