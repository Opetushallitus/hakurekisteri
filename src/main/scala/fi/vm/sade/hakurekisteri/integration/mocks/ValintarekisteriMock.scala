package fi.vm.sade.hakurekisteri.integration.mocks

import fi.vm.sade.hakurekisteri.integration.mocks.HenkiloMock._

object ValintarekisteriMock {
  def getHistoria(henkiloOid: String): String = {
    henkiloOid match {
      case "1.2.246.562.24.71944845619" =>
        getResourceJson("/mock-data/valintarekisteri/vastaanottotiedot-aarne.json")
      case any => """{"opintopolku":[],"vanhat":[]}"""
    }
  }
}
