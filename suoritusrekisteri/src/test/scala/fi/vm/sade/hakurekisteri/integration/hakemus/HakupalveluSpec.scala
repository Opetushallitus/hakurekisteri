package fi.vm.sade.hakurekisteri.integration.hakemus

import fi.vm.sade.hakurekisteri.suoritus.{VirallinenSuoritus, yksilollistaminen}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class HakupalveluSpec extends AnyFlatSpec with Matchers {

  it should "use komo koodi for TUVA10" in {
    val suoritus: VirallinenSuoritus = createSuoritus("TUVA10")
    suoritus.komo should be("TUVA10")
    suoritus.yksilollistaminen should be(yksilollistaminen.Ei)
    suoritus.tila should be("VALMIS")
  }

  it should "use komo koodi for APT" in {
    val suoritus: VirallinenSuoritus = createSuoritus("APT")
    suoritus.komo should be("APT")
    suoritus.yksilollistaminen should be(yksilollistaminen.Ei)
    suoritus.tila should be("VALMIS")
  }

  it should "use komo koodi for ATEAT" in {
    val suoritus: VirallinenSuoritus = createSuoritus("ATEAT")
    suoritus.komo should be("ATEAT")
    suoritus.yksilollistaminen should be(yksilollistaminen.Ei)
    suoritus.tila should be("VALMIS")
  }

  it should "use komo koodi for KK" in {
    val suoritus: VirallinenSuoritus = createSuoritus("KK")
    suoritus.komo should be("KK")
    suoritus.yksilollistaminen should be(yksilollistaminen.Ei)
    suoritus.tila should be("VALMIS")
  }

  it should "use komo koodi for YO" in {
    val suoritus: VirallinenSuoritus = createSuoritus("YO")
    suoritus.komo should be("YO")
    suoritus.yksilollistaminen should be(yksilollistaminen.Ei)
    suoritus.tila should be("VALMIS")
  }

  it should "use komo koodi for EIPT" in {
    val suoritus: VirallinenSuoritus = createSuoritus("EIPT")
    suoritus.komo should be("EIPT")
    suoritus.yksilollistaminen should be(yksilollistaminen.Ei)
    suoritus.tila should be("KESKEN")
  }

  it should "use komo koodi for PO" in {
    val suoritus: VirallinenSuoritus = createSuoritus("PO")
    suoritus.komo should be("PO")
    suoritus.yksilollistaminen should be(yksilollistaminen.Ei)
    suoritus.tila should be("VALMIS")
  }

  it should "use komo koodi for POY" in {
    val suoritus: VirallinenSuoritus = createSuoritus("POY")
    suoritus.komo should be("POY")
    suoritus.yksilollistaminen should be(yksilollistaminen.Osittain)
    suoritus.tila should be("VALMIS")
  }

  it should "use komo koodi for PKYO" in {
    val suoritus: VirallinenSuoritus = createSuoritus("PKYO")
    suoritus.komo should be("PKYO")
    suoritus.yksilollistaminen should be(yksilollistaminen.Kokonaan)
    suoritus.tila should be("VALMIS")
  }

  it should "use komo koodi for PYOT" in {
    val suoritus: VirallinenSuoritus = createSuoritus("PYOT")
    suoritus.komo should be("PYOT")
    suoritus.yksilollistaminen should be(yksilollistaminen.Alueittain)
    suoritus.tila should be("VALMIS")
  }

  it should "use komo koodi for UK" in {
    val suoritus: VirallinenSuoritus = createSuoritus("UK")
    suoritus.komo should be("UK")
    suoritus.yksilollistaminen should be(yksilollistaminen.Ei)
    suoritus.tila should be("VALMIS")
  }

  def createSuoritus(komo: String): VirallinenSuoritus = {
    AkkaHakupalvelu
      .getSuoritus(Some(komo), "SPEC", null, "SUORITTAJA", "FI", Some("HAKIJA"))
      .get
      .asInstanceOf[VirallinenSuoritus]
  }

}
