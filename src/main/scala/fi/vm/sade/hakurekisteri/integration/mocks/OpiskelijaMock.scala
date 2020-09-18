package fi.vm.sade.hakurekisteri.integration.mocks

object OpiskelijaMock {
  def getResourceJson(filename: String): String = {
    scala.io.Source
      .fromInputStream(getClass.getResourceAsStream(filename))
      .getLines()
      .mkString
  }

  def getOpiskelijaByOid(henkiloOid: String): String = {
    Map(
      "1.2.246.562.24.71944845619" -> getResourceJson(
        "/mock-data/opiskelija/opiskelija-aarne.json"
      ),
      "1.2.246.562.24.98743797763" -> getResourceJson("/mock-data/opiskelija/opiskelija-tyyne.json")
    )(henkiloOid)
  }
}
