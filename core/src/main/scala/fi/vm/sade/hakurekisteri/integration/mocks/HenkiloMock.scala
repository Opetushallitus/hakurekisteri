package fi.vm.sade.hakurekisteri.integration.mocks


object HenkiloMock {

  private def getResourceJson(filename: String): String = {
    scala.io.Source.fromInputStream(getClass.getResourceAsStream(filename))
      .getLines
      .mkString
  }

  def oidListAll() = getResourceJson("/mock-data/henkilo-all-oidList.json")

  def aarneOidList() = getResourceJson("/mock-data/henkilo-aarne-oidList.json")

  def aarneHetuHaku() = getResourceJson("/mock-data/henkilo-aarne-hetuhaku.json")

  def tyyneHetuHaku() = getResourceJson("/mock-data/henkilo-tyyne-hetuhaku.json")

  def aarne() = getResourceJson("/mock-data/henkilo-aarne.json")

  def tyyne() = getResourceJson("/mock-data/henkilo-tyyne.json")

}
