package fi.vm.sade.hakurekisteri.tools

import com.github.nscala_time.time.TypeImports._
import fi.vm.sade.hakurekisteri.rest.support.Kausi
import fi.vm.sade.hakurekisteri.suoritus.{yksilollistaminen, VirallinenSuoritus, Komoto}
import scala.Some

object PerusopetuksenToteutus2005S {
  def apply(oppilaitos: String): Komoto = {
    Komoto("komotoid", "peruskoulu", oppilaitos, Some("2005"), Some(Kausi.Syksy))
  }
}
object Peruskoulu {
  def apply(
    oppilaitos: String,
    tila: String,
    valmistuminen: LocalDate,
    henkiloOid: String
  ): VirallinenSuoritus = {
    VirallinenSuoritus(
      "peruskoulu",
      oppilaitos,
      tila,
      valmistuminen,
      henkiloOid,
      yksilollistaminen.Ei,
      "fi",
      lahde = "Test"
    )
  }
}
object OsittainYksilollistettyPerusopetus {
  def apply(
    oppilaitos: String,
    tila: String,
    valmistuminen: LocalDate,
    henkiloOid: String
  ): VirallinenSuoritus = {
    VirallinenSuoritus(
      "peruskoulu",
      oppilaitos,
      tila,
      valmistuminen,
      henkiloOid,
      yksilollistaminen.Osittain,
      "fi",
      lahde = "Test"
    )
  }
}
object AlueittainYksilollistettyPerusopetus {
  def apply(
    oppilaitos: String,
    tila: String,
    valmistuminen: LocalDate,
    henkiloOid: String
  ): VirallinenSuoritus = {
    VirallinenSuoritus(
      "peruskoulu",
      oppilaitos,
      tila,
      valmistuminen,
      henkiloOid,
      yksilollistaminen.Alueittain,
      "fi",
      lahde = "Test"
    )
  }
}
object KokonaanYksillollistettyPerusopetus {
  def apply(
    oppilaitos: String,
    tila: String,
    valmistuminen: LocalDate,
    henkiloOid: String
  ): VirallinenSuoritus = {
    VirallinenSuoritus(
      "peruskoulu",
      oppilaitos,
      tila,
      valmistuminen,
      henkiloOid,
      yksilollistaminen.Kokonaan,
      "fi",
      lahde = "Test"
    )
  }
}
