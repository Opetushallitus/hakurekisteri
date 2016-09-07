package fi.vm.sade.hakurekisteri.integration.mocks

import fi.vm.sade.hakurekisteri.integration.mocks.HenkiloMock._

object KoodistoMock {

  def getKoodisto(): String = {
    getResourceJson("/mock-data/koodisto/koodisto.json")
  }

}
