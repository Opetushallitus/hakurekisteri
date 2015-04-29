package fi.vm.sade.hakurekisteri.integration

import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.hakurekisteri.{CleanSharedJettyBeforeEach, SharedJetty}
import org.joda.time.DateTime
import org.json4s.JArray
import org.json4s.JsonAST.JObject
import org.scalatest._
import org.scalatra.test.HttpComponentsClient
import org.json4s.jackson.JsonMethods._

trait Henkilo extends SuiteMixin with Matchers with HttpComponentsClient { this: Suite =>
  val henkilo: String

  abstract override def withFixture(test: NoArgTest) = {
    val baseUrl = s"http://localhost:${SharedJetty.port}"
    post("/rest/v1/opiskelijat", henkilo, Map("Content-Type" -> "application/json; charset=UTF-8")) {
      response.status should be(201)
    }
    super.withFixture(test)
  }
}

trait Matti extends Henkilo { this: Suite =>
  override val henkilo = """{
  "oppilaitosOid": "1.2.246.562.10.96421158856",
  "luokkataso": "lukio",
  "luokka": "1",
  "henkiloOid": "1.2.246.562.24.12345678910",
  "alkuPaiva": "2015-01-01T10:00:00.00Z",
  "loppuPaiva": "2016-01-01T10:00:00.00Z"
}"""
}

trait Teppo extends Henkilo { this: Suite =>
  override val henkilo = """{
  "oppilaitosOid": "1.2.246.562.10.96421158856",
  "luokkataso": "lukio",
  "luokka": "1",
  "henkiloOid": "1.2.246.562.24.12345678911",
  "alkuPaiva": "2015-01-01T10:00:00.00Z",
  "loppuPaiva": "2016-01-01T10:00:00.00Z"
}"""
}

class SuoritusResourceIntegrationSpec extends FlatSpec with Matti with Teppo with CleanSharedJettyBeforeEach with Matchers with HakurekisteriJsonSupport {
  val contentTypeJson = Map("Content-Type" -> "application/json; charset=UTF-8")
  val matinSuoritus = """{
  "komo": "1.2.246.562.5.2013061010184237348007",
  "myontaja": "1.2.246.562.10.96421158856",
  "tila": "KESKEN",
  "valmistuminen": "01.01.2016",
  "henkiloOid": "1.2.246.562.24.12345678910",
  "suoritusKieli": "FI"
}"""
  val teponSuoritus = """{
  "komo": "1.2.246.562.5.2013061010184237348007",
  "myontaja": "1.2.246.562.10.96421158856",
  "tila": "KESKEN",
  "valmistuminen": "01.01.2016",
  "henkiloOid": "1.2.246.562.24.12345678911",
  "suoritusKieli": "FI"
}"""
  val matinValmistuminen = """{
  "komo": "1.2.246.562.5.2013061010184237348007",
  "myontaja": "1.2.246.562.10.96421158856",
  "tila": "VALMIS",
  "valmistuminen": "01.01.2016",
  "henkiloOid": "1.2.246.562.24.12345678910",
  "suoritusKieli": "FI"
}"""

  behavior of "SuoritusResource"

  it should "only return Suoritukset modified after muokattuJalkeen" in {
    val before = DateTime.now()
    post("/rest/v1/suoritukset", matinSuoritus, contentTypeJson) {
      response.status should be(201)
    }
    val after = DateTime.now()
    get("/rest/v1/suoritukset", ("muokattuJalkeen", before.toString)) {
      response.status should be(200)
      parse(response.body).extract[JArray].arr.length should be(1)
    }
    get("/rest/v1/suoritukset", ("muokattuJalkeen", after.toString)) {
      response.status should be(200)
      parse(response.body).extract[JArray].arr shouldBe empty
    }

    post("/rest/v1/suoritukset", matinValmistuminen, contentTypeJson) {
      response.status should be(201)
    }
    get("/rest/v1/suoritukset", ("muokattuJalkeen", after.toString)) {
      response.status should be(200)
      val result = parse(response.body).extract[JArray].arr
      result.length should be(1)
      result.head.extract[JObject].values("tila") should be("VALMIS")
    }
  }

  it should "handle Suoritukset by multiple persons correctly" in {
    val before = DateTime.now()
    post("/rest/v1/suoritukset", matinSuoritus, contentTypeJson) {
      response.status should be(201)
    }
    val afterMatti = DateTime.now()
    post("/rest/v1/suoritukset", teponSuoritus, contentTypeJson) {
      response.status should be(201)
    }
    val after = DateTime.now()
    get("/rest/v1/suoritukset", ("muokattuJalkeen", before.toString)) {
      response.status should be(200)
      parse(response.body).extract[JArray].arr.length should be(2)
    }
    get("/rest/v1/suoritukset", ("muokattuJalkeen", afterMatti.toString)) {
      response.status should be(200)
      val result = parse(response.body).extract[JArray].arr
      result.length should be(1)
      result.head.extract[JObject].values("henkiloOid") should be("1.2.246.562.24.12345678911")
    }
    get("/rest/v1/suoritukset", ("muokattuJalkeen", after.toString)) {
      response.status should be(200)
      parse(response.body).extract[JArray].arr shouldBe empty
    }
  }
}
