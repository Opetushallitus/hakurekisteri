package fi.vm.sade.hakurekisteri.integration

import fi.vm.sade.hakurekisteri.{SharedJetty, Config, HakuRekisteriJetty}
import org.scalatest.{Matchers, FlatSpec}
import org.scalatra.test.HttpComponentsClient

class SuoritusResourceIntegrationSpec extends FlatSpec with Matchers with HttpComponentsClient {
  val port = SharedJetty.port
  val baseUrl = s"http://localhost:${port}"

  it should "asdf" in {
    SharedJetty.start
    get("/") {
      response.status should be(200)
    }
  }
}
