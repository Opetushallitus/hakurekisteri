package fi.vm.sade.hakurekisteri.integration.mocks

import fi.vm.sade.hakurekisteri.integration.mocks.HenkiloMock._

object OrganisaatioMock {

  def findAll(): String = {
    getResourceJson("/mock-data/organisaatio/organisaatio-all.json")
  }

  def findByOid(tunniste: String): String = {
    if (tunniste.equals("06345") || tunniste.equals("1.2.246.562.10.39644336305")) {
      getResourceJson("/mock-data/organisaatio/organisaatio-pikkarala.json")
    } else if (tunniste.equals("05127") || tunniste.equals("1.2.246.562.10.16546622305")) {
      getResourceJson("/mock-data/organisaatio/organisaatio-pikkola.json")
    } else {
      getResourceJson("/mock-data/organisaatio/organisaatio-foobar.json")
    }
  }
}
