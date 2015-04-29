package fi.vm.sade.hakurekisteri.integration

import fi.vm.sade.hakurekisteri._
import org.scalatest.{Matchers, FlatSpec}

class SuoritusResourceIntegrationSpec extends FlatSpec with CleanSharedJettyBeforeEach with Matchers {
  it should "asdf" in {
    get("/") {
      response.status should be(200)
    }
  }
}
