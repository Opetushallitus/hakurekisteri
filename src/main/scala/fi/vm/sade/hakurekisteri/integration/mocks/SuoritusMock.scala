package fi.vm.sade.hakurekisteri.integration.mocks

object SuoritusMock {
  def getResourceJson(filename: String): String = {
    scala.io.Source
      .fromInputStream(getClass.getResourceAsStream(filename))
      .getLines()
      .mkString
  }

  def getSuoritusByHenkiloKomoTila(henkiloOid: String, komo: String, tila: String): String = {
    Map(
      "1.2.246.562.24.71944845619" ->
        Map(
          "1.2.246.562.5.2013061010184237348007" ->
            Map(
              "KESKEN" -> getResourceJson("/mock-data/suoritus/suoritus-aarne-lukio-kesken.json"),
              "VALMIS" -> getResourceJson("/mock-data/suoritus/suoritus-aarne-lukio-valmis.json")
            )
        ),
      "1.2.246.562.24.98743797763" ->
        Map(
          "1.2.246.562.5.2013061010184237348007" ->
            Map(
              "KESKEN" -> getResourceJson("/mock-data/suoritus/suoritus-tyyne-lukio-kesken.json")
            ),
          "1.2.246.562.13.62959769647" ->
            Map(
              "KESKEN" -> getResourceJson(
                "/mock-data/suoritus/suoritus-tyyne-peruskoulu-kesken.json"
              )
            )
        )
    )(henkiloOid)(komo)(tila)
  }
}
