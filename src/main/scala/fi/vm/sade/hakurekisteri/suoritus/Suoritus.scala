package fi.vm.sade.hakurekisteri.suoritus

import java.util.UUID
import fi.vm.sade.hakurekisteri.storage.Identified
import org.joda.time.LocalDate
import fi.vm.sade.hakurekisteri.rest.support.UUIDResource
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

sealed abstract class Suoritus(val henkiloOid: String, val vahvistettu: Boolean, val source: String) extends UUIDResource[Suoritus]{

}

case class VapaamuotoinenSuoritus(henkilo: String, kuvaus: String, myontaja: String, vuosi: Int, tyyppi: String, index: Int = 0, lahde: String) extends Suoritus (henkilo, false, lahde) {

  private[VapaamuotoinenSuoritus] case class VapaaSisalto(henkilo: String, tyyppi: String, index: Int)

  val kkTutkinto = tyyppi == "kkTutkinto"

  private[VapaamuotoinenSuoritus] val core = VapaaSisalto(henkilo, tyyppi, index)


  override def identify(identity: UUID): VapaamuotoinenSuoritus with Identified[UUID] = new VapaamuotoinenSuoritus(henkiloOid, kuvaus, myontaja, vuosi, tyyppi, index, source) with Identified[UUID] {
    val id: UUID = identity
  }

  override def hashCode(): Int = core.hashCode()

  override def equals(obj: scala.Any): Boolean = obj.isInstanceOf[VapaamuotoinenSuoritus] && core.equals(obj.asInstanceOf[VapaamuotoinenSuoritus].core)
}

object VapaamuotoinenKkTutkinto {

  def apply(henkilo: String, kuvaus: String, myontaja: String, vuosi: Int, index: Int = 0, lahde: String) =
    VapaamuotoinenSuoritus(henkilo: String, kuvaus: String, myontaja: String, vuosi: Int, "kkTutkinto", index: Int, lahde: String)


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

  private[VirallinenSuoritus] case class VirallinenSisalto(henkilo: String, komo: String, myontaja: String)

  val core = VirallinenSisalto(henkilo, komo, myontaja)

  override def equals(obj: scala.Any): Boolean = obj.isInstanceOf[VirallinenSuoritus] && core.equals(obj.asInstanceOf[VirallinenSuoritus].core)



  override def identify(identity: UUID): VirallinenSuoritus with Identified[UUID] = new VirallinenSuoritus(komo, myontaja, tila, valmistuminen, henkiloOid, yksilollistaminen, suoritusKieli, opiskeluoikeus, vahvistettu, source) with Identified[UUID] {
    val id: UUID = identity
  }
}


