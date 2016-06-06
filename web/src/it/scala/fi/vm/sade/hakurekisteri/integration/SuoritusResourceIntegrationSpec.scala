package fi.vm.sade.hakurekisteri.integration

import fi.vm.sade.hakurekisteri.CleanSharedJettyBeforeEach
import fi.vm.sade.hakurekisteri.integration.mocks.SuoritusMock
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import org.joda.time.DateTime
import org.json4s.JArray
import org.json4s.JsonAST.JObject
import org.json4s.jackson.JsonMethods._
import org.scalatest._

class SuoritusResourceIntegrationSpec extends FlatSpec with CleanSharedJettyBeforeEach with Matchers with HakurekisteriJsonSupport {
  val lukioKomo = "1.2.246.562.5.2013061010184237348007"
  val peruskouluKomo = "1.2.246.562.13.62959769647"
  val aarnenLukio = SuoritusMock.getSuoritusByHenkiloKomoTila("1.2.246.562.24.71944845619", lukioKomo, "KESKEN")
  val tyynenLukio = SuoritusMock.getSuoritusByHenkiloKomoTila("1.2.246.562.24.98743797763", lukioKomo, "KESKEN")
  val aarnenLukioValmistuminen = SuoritusMock.getSuoritusByHenkiloKomoTila("1.2.246.562.24.71944845619", lukioKomo, "VALMIS")
  val tyynenPeruskoulu = SuoritusMock.getSuoritusByHenkiloKomoTila("1.2.246.562.24.98743797763", peruskouluKomo, "KESKEN")

  def postSuoritus(suoritus: String): String =
    post("/rest/v1/suoritukset", suoritus, Map("Content-Type" -> "application/json; charset=UTF-8")) {
      response.status should be(201)
      parse(body).extract[JObject].values("id").asInstanceOf[String]
    }

  def responseIds(body: String): Seq[String] = parse(body).extract[JArray].arr
    .map(_.extract[JObject].values("id").asInstanceOf[String])

  behavior of "SuoritusResource"

  it should "only return Suoritukset modified after muokattuJalkeen" in {
    val before = DateTime.now()
    val aarnenLukioId = postSuoritus(aarnenLukio)
    val after = DateTime.now()
    get("/rest/v1/suoritukset", ("muokattuJalkeen", before.toString)) {
      response.status should be(200)
      parse(response.body).extract[JArray].arr.length should be(1)
      responseIds(response.body) should contain(aarnenLukioId)
    }
    get("/rest/v1/suoritukset", ("muokattuJalkeen", after.toString)) {
      response.status should be(200)
      parse(response.body).extract[JArray].arr shouldBe empty
    }
  }

  it should "handle Suoritukset by multiple persons correctly when queried with muokattuJalkeen" in {
    val before = DateTime.now()
    val aarnenLukioId = postSuoritus(aarnenLukio)
    val afterAarne = DateTime.now()
    val tyynenLukioId = postSuoritus(tyynenLukio)
    val after = DateTime.now()
    get("/rest/v1/suoritukset", ("muokattuJalkeen", before.toString)) {
      response.status should be(200)
      parse(response.body).extract[JArray].arr.length should be(2)
      val ids = responseIds(response.body)
      ids should contain(aarnenLukioId)
      ids should contain(tyynenLukioId)
    }
    get("/rest/v1/suoritukset", ("muokattuJalkeen", afterAarne.toString)) {
      response.status should be(200)
      parse(response.body).extract[JArray].arr.length should be(1)
      responseIds(response.body) should contain(tyynenLukioId)
    }
    get("/rest/v1/suoritukset", ("muokattuJalkeen", after.toString)) {
      response.status should be(200)
      parse(response.body).extract[JArray].arr shouldBe empty
    }
  }

  it should "support queries by komo" in {
    val aarnenLukioId = postSuoritus(aarnenLukio)
    val tyynenLukioId = postSuoritus(tyynenLukio)
    val tyynenPeruskouluId = postSuoritus(tyynenPeruskoulu)
    get("/rest/v1/suoritukset", ("komo", lukioKomo)) {
      parse(response.body).extract[JArray].arr.length should be(2)
      val ids = responseIds(response.body)
      ids should contain(aarnenLukioId)
      ids should contain(tyynenLukioId)
    }
    get("/rest/v1/suoritukset", ("komo", peruskouluKomo)) {
      response.status should be(200)
      parse(response.body).extract[JArray].arr.length should be(1)
      responseIds(response.body) should contain(tyynenPeruskouluId)
    }
  }

  it should "support queries by komo and muokattuJalkeen" in {
    val aarnenLukioId = postSuoritus(aarnenLukio)
    val afterAarne = DateTime.now()
    val tyynenLukioId = postSuoritus(tyynenLukio)
    val tyynenPeruskouluId = postSuoritus(tyynenPeruskoulu)
    val afterTyyne = DateTime.now()
    val aarnenLukioValmistuminenId = postSuoritus(aarnenLukioValmistuminen)
    get("/rest/v1/suoritukset", ("komo", lukioKomo), ("muokattuJalkeen", afterAarne.toString)) {
      response.status should be(200)
      parse(response.body).extract[JArray].arr.length should be(2)
      val ids = responseIds(response.body)
      ids should contain(tyynenLukioId)
      ids should contain(aarnenLukioValmistuminenId)
    }
    get("/rest/v1/suoritukset", ("komo", lukioKomo), ("muokattuJalkeen", afterTyyne.toString)) {
      response.status should be(200)
      parse(response.body).extract[JArray].arr.length should be(1)
      responseIds(response.body) should contain(aarnenLukioValmistuminenId)
    }
    get("/rest/v1/suoritukset", ("komo", peruskouluKomo), ("muokattuJalkeen", afterAarne.toString)) {
      response.status should be(200)
      parse(response.body).extract[JArray].arr.length should be(1)
      responseIds(response.body) should contain(tyynenPeruskouluId)
    }
    get("/rest/v1/suoritukset", ("komo", peruskouluKomo), ("muokattuJalkeen", afterTyyne.toString)) {
      response.status should be(200)
      parse(response.body).extract[JArray].arr shouldBe empty
    }
  }
}
