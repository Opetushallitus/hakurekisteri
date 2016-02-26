package fi.vm.sade.hakurekisteri.integration

import org.scalatest.BeforeAndAfterEach

trait LocalhostProperties extends BeforeAndAfterEach {
  this : LocalhostProperties with org.scalatest.Suite =>
  override def beforeEach() {
    super.beforeEach()
    OphUrlProperties.ophProperties.defaultOverrides.setProperty("baseUrl","http://localhost")
    OphUrlProperties.ophProperties.reload()
  }

  override def afterEach() {
    super.afterEach()
    OphUrlProperties.ophProperties.defaultOverrides.clear()
  }
}
