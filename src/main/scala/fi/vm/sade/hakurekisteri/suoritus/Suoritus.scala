package fi.vm.sade.hakurekisteri.suoritus

import java.util.UUID

import fi.vm.sade.hakurekisteri.Oids
import fi.vm.sade.hakurekisteri.rest.support.Kausi.Kausi
import fi.vm.sade.hakurekisteri.rest.support.UUIDResource
import fi.vm.sade.hakurekisteri.storage.Identified
import org.joda.time.{DateTimeConstants, LocalDate}

import scala.annotation.tailrec

object yksilollistaminen extends Enumeration {
  type Yksilollistetty = Value
  val Ei = Value("Ei")
  val Osittain = Value("Osittain")
  val Kokonaan = Value("Kokonaan")
  val Alueittain = Value("Alueittain")
}

import fi.vm.sade.hakurekisteri.suoritus.yksilollistaminen._

case class Komoto(oid: String, komo: String, tarjoaja: String, alkamisvuosi: Option[String], alkamiskausi: Option[Kausi])

sealed abstract class Suoritus(val henkiloOid: String, val vahvistettu: Boolean, val source: String) extends UUIDResource[Suoritus]{

}

case class VapaamuotoinenSuoritus(henkilo: String, kuvaus: String, myontaja: String, vuosi: Int, tyyppi: String, index: Int = 0, lahde: String) extends Suoritus (henkilo, false, lahde) {

  private[VapaamuotoinenSuoritus] case class VapaaSisalto(henkilo: String, tyyppi: String, index: Int)

  val kkTutkinto = tyyppi == "kkTutkinto"

  override val core = VapaaSisalto(henkilo, tyyppi, index)


  override def identify(identity: UUID): VapaamuotoinenSuoritus with Identified[UUID] = new VapaamuotoinenSuoritus(henkiloOid, kuvaus, myontaja, vuosi, tyyppi, index, source) with Identified[UUID] {
    val id: UUID = identity
  }


}

object VapaamuotoinenKkTutkinto {

  def apply(henkilo: String, kuvaus: String, myontaja: String, vuosi: Int, index: Int = 0, lahde: String) =
    VapaamuotoinenSuoritus(henkilo: String, kuvaus: String, myontaja: String, vuosi: Int, "kkTutkinto", index: Int, lahde: String)


}

object AmmatillisenKielikoeSuoritus {
  val tyyppi = "ammatillisenKielikoe"
}

case class AmmatillisenKielikoeSuoritus(henkilo: String, kuvaus: String, myontaja: String, vuosi: Int, lahde: String) extends Suoritus (henkilo, false, lahde) {

  private[AmmatillisenKielikoeSuoritus] case class VapaaSisalto(henkilo: String, tyyppi: String)

  override val core = VapaaSisalto(henkilo, AmmatillisenKielikoeSuoritus.tyyppi)

  override def identify(identity: UUID): AmmatillisenKielikoeSuoritus with Identified[UUID] = new AmmatillisenKielikoeSuoritus(henkiloOid, kuvaus, myontaja, vuosi, source) with Identified[UUID] {
    val id: UUID = identity
  }
}


object DayFinder {

  def basedate = LocalDate.now()

  import com.github.nscala_time.time.Imports._

  def saturdayOfWeek22(year: Int) = {
    basedate.withWeekyear(year).withWeekOfWeekyear(22).withDayOfWeek(DateTimeConstants.SATURDAY)
  }

  @tailrec
  def firstSaturdayAfter(startDate: LocalDate): LocalDate = startDate.getDayOfWeek match {
    case DateTimeConstants.SATURDAY => startDate
    case _ => firstSaturdayAfter(startDate.plusDays(1))
  }
}

import fi.vm.sade.hakurekisteri.suoritus.DayFinder._

object ItseilmoitettuPeruskouluTutkinto {

  def apply(hakemusOid: String, hakijaOid: String, valmistumisvuosi: Int, suoritusKieli: String) =
    VirallinenSuoritus(
      Oids.perusopetusKomoOid,
      myontaja = hakemusOid,
      tila = "VALMIS",
      valmistuminen = saturdayOfWeek22(valmistumisvuosi),
      hakijaOid,
      yksilollistaminen = Ei,
      suoritusKieli,
      opiskeluoikeus = None,
      vahv = false,
      lahde = hakijaOid
    )

}

object ItseilmoitettuTutkinto {

  def apply(komoOid: String, hakemusOid: String, hakijaOid: String, valmistumisvuosi: Int, suoritusKieli: String) =
    VirallinenSuoritus(
      komo = komoOid, //Config.lisaopetusKomoOid,
      myontaja = hakemusOid,
      tila = "VALMIS",
      valmistuminen = saturdayOfWeek22(valmistumisvuosi),
      hakijaOid,
      yksilollistaminen = Ei,
      suoritusKieli,
      opiskeluoikeus = None,
      vahv = false,
      lahde = hakijaOid
    )

}

object ItseilmoitettuLukioTutkinto {

  def apply(myontaja: String, hakijaOid: String, valmistumisvuosi: Int, suoritusKieli: String) =
    VirallinenSuoritus(
      Oids.lukioKomoOid,
      myontaja = myontaja,
      tila = "VALMIS",
      valmistuminen = saturdayOfWeek22(valmistumisvuosi),
      hakijaOid,
      yksilollistaminen = Ei,
      suoritusKieli,
      opiskeluoikeus = None,
      vahv = false,
      lahde = hakijaOid
    )

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

  private[VirallinenSuoritus] case class VirallinenSisalto(henkilo: String, komo: String, myontaja: String, vahv: Boolean)

  override  val core = VirallinenSisalto(henkilo, komo, myontaja, vahv)

  override def identify(identity: UUID): VirallinenSuoritus with Identified[UUID] = new VirallinenSuoritus(komo, myontaja, tila, valmistuminen, henkiloOid, yksilollistaminen, suoritusKieli, opiskeluoikeus, vahvistettu, source) with Identified[UUID] {
    val id: UUID = identity
  }
}


