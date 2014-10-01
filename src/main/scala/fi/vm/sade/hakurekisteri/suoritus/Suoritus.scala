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

sealed abstract class Suoritus(val henkiloOid: String, val vahvistettu: Boolean, val source: String) extends Resource[UUID]{

}



case class VapaamuotoinenSuoritus(henkilo: String, kuvaus: String, myontaja: String, vuosi: Int, tyyppi: String, lahde: String) extends Suoritus (henkilo, false, lahde) {

  val kkTutkinto = tyyppi == "kkTutkinto"

  override def identify(id: UUID): this.type with Identified[UUID] = VapaamuotoinenSuoritus.identify(this,id).asInstanceOf[this.type with Identified[UUID]]

}

object VapaamuotoinenSuoritus {
  def identify(o: VapaamuotoinenSuoritus): VapaamuotoinenSuoritus with Identified[UUID] = o match {
    case o: Suoritus with Identified[UUID] => o
    case _ => o.identify(UUID.randomUUID)
  }

  def identify(o: VapaamuotoinenSuoritus, identity: UUID) = {
    new VapaamuotoinenSuoritus(o.henkiloOid, o.kuvaus, o.myontaja, o.vuosi, o.tyyppi,  o.source) with Identified[UUID] {
      val id: UUID = identity
    }
  }
}

case class VirallinenSuoritus(komo: String,
                    myontaja: String,
                    tila: String,
                    valmistuminen: LocalDate,
                    henkilo: String,
                    yksilollistaminen: Yksilollistetty,
                    suoritusKieli: String,
                    opiskeluoikeus: Option[UUID] = None,
                    vahv:Boolean = true,
                    lahde: String) extends Suoritus(henkilo, vahv, lahde)  {
  override def identify(id: UUID): this.type with Identified[UUID] = VirallinenSuoritus.identify(this,id).asInstanceOf[this.type with Identified[UUID]]
}

object VirallinenSuoritus {
  def identify(o: VirallinenSuoritus): VirallinenSuoritus with Identified[UUID] = o match {
    case o: Suoritus with Identified[UUID] => o
    case _ => o.identify(UUID.randomUUID)
  }

  def identify(o: VirallinenSuoritus, identity: UUID) = {
    new VirallinenSuoritus(o.komo, o.myontaja, o.tila, o.valmistuminen, o.henkiloOid, o.yksilollistaminen, o.suoritusKieli, o.opiskeluoikeus, o.vahvistettu, o.source) with Identified[UUID] {
      val id: UUID = identity
    }
  }
}

