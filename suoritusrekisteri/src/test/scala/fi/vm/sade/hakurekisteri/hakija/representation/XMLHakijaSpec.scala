package fi.vm.sade.hakurekisteri.hakija.representation

import fi.vm.sade.hakurekisteri.suoritus.{VirallinenSuoritus, yksilollistaminen}
import org.joda.time.LocalDate
import org.scalatest.{FlatSpec, Matchers}

class XMLHakijaSpec extends FlatSpec with Matchers {

  def createSuoritus(pohjakoulutus: String): VirallinenSuoritus = {
    VirallinenSuoritus(
      pohjakoulutus,
      "",
      "",
      LocalDate.now,
      "",
      yksilollistaminen.Ei,
      "",
      None,
      vahv = false,
      ""
    )
  }

  it should "return suoritus with smallest pohjakoulutus value" in {
    XMLHakemus
      .getRelevantSuoritus(Seq(createSuoritus("0"), createSuoritus("2")))
      .get
      .komo should be("0")
    XMLHakemus
      .getRelevantSuoritus(Seq(createSuoritus("2"), createSuoritus("0")))
      .get
      .komo should be("0")
  }

  it should "return suoritus with string value over suoritus with value 7" in {
    XMLHakemus
      .getRelevantSuoritus(Seq(createSuoritus("ATEAT"), createSuoritus("7")))
      .get
      .komo should be("ATEAT")
    XMLHakemus
      .getRelevantSuoritus(Seq(createSuoritus("7"), createSuoritus("ATEAT")))
      .get
      .komo should be("ATEAT")
  }

  it should "return suoritus with string value over larger string value" in {
    XMLHakemus
      .getRelevantSuoritus(Seq(createSuoritus("ATEAT"), createSuoritus("KK")))
      .get
      .komo should be("ATEAT")
    XMLHakemus
      .getRelevantSuoritus(Seq(createSuoritus("KK"), createSuoritus("ATEAT")))
      .get
      .komo should be("ATEAT")
  }

}
