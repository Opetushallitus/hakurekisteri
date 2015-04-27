package fi.vm.sade.hakurekisteri.integration.mocks

object OrganisaatioMock {

  def findAll(): String = {
    scala.io.Source.fromInputStream(getClass.getResourceAsStream("/mock-data/organisaatio-all.json"))
      .getLines
      .mkString
  }

  def findByOid(tunniste: String): String = {
    if (tunniste.equals("06345") || tunniste.equals("1.2.246.562.10.39644336305")) {
      scala.io.Source.fromInputStream(getClass.getResourceAsStream("/mock-data/organisaatio-pikkarala.json"))
        .getLines
        .mkString
    } else if (tunniste.equals("05127") || tunniste.equals("1.2.246.562.10.16546622305")) {
      scala.io.Source.fromInputStream(getClass.getResourceAsStream("/mock-data/organisaatio-pikkola.json"))
        .getLines
        .mkString
    } else {
      scala.io.Source.fromInputStream(getClass.getResourceAsStream("/mock-data/organisaatio-foobar.json"))
        .getLines
        .mkString
    }
  }
}
