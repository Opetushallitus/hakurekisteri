package fi.vm.sade.hakurekisteri.integration.mocks

import fi.vm.sade.hakurekisteri.integration.mocks.HenkiloMock._

object OrganisaatioMock {

  def ryhmat(): String = {
    getResourceJson("/mock-data/organisaatio/organisaatio-ryhmat.json")
  }

  def findAll(): String = {
    getResourceJson("/mock-data/organisaatio/organisaatio-all.json")
  }

  def findByOid(tunniste: String): String = {
    if (tunniste == "06345" || tunniste == "1.2.246.562.10.39644336305") {
      getResourceJson("/mock-data/organisaatio/organisaatio-pikkarala.json")
    } else if (tunniste == "05127" || tunniste == "1.2.246.562.10.16546622305") {
      getResourceJson("/mock-data/organisaatio/organisaatio-pikkola.json")
    } else if (tunniste == "01915" || tunniste == "1.2.246.562.10.259480292910") {
      getResourceJson("/mock-data/organisaatio/organisaatio-tampere.json")
    } else {
      getResourceJson("/mock-data/organisaatio/organisaatio-foobar.json")
    }
  }
}
