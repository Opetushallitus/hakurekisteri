package fi.vm.sade.hakurekisteri.integration

import java.util.UUID

import fi.vm.sade.hakurekisteri.arvosana.{ArvioHyvaksytty, Arvosana}
import fi.vm.sade.hakurekisteri.integration.henkilo.MockOppijaNumeroRekisteri
import fi.vm.sade.hakurekisteri.integration.mocks.SuoritusMock
import fi.vm.sade.hakurekisteri.rest.support.{
  HakurekisteriJsonSupport,
  SuoritusDeserializer,
  SuoritusSerializer
}
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, VirallinenSuoritus, yksilollistaminen}
import fi.vm.sade.hakurekisteri.{CleanSharedTestJettyBeforeEach, KomoOids}
import org.joda.time.{DateTime, LocalDate}
import org.json4s.JsonAST.JObject
import org.json4s.jackson.JsonMethods._
import org.json4s.{JArray, JValue}
import org.scalatest._

class SuoritusResourceIntegrationSpec
    extends FlatSpec
    with CleanSharedTestJettyBeforeEach
    with Matchers
    with HakurekisteriJsonSupport {
  override implicit def jsonFormats = super.jsonFormats ++ List(new SuoritusDeserializer)

  val lukioKomo = "1.2.246.562.5.2013061010184237348007"
  val peruskouluKomo = "1.2.246.562.13.62959769647"
  private val aarnenOid: String = "1.2.246.562.24.71944845619"
  val aarnenLukio = SuoritusMock.getSuoritusByHenkiloKomoTila(aarnenOid, lukioKomo, "KESKEN")
  val tyynenLukio =
    SuoritusMock.getSuoritusByHenkiloKomoTila("1.2.246.562.24.98743797763", lukioKomo, "KESKEN")
  val aarnenLukioValmistuminen =
    SuoritusMock.getSuoritusByHenkiloKomoTila(aarnenOid, lukioKomo, "VALMIS")
  val tyynenPeruskoulu = SuoritusMock.getSuoritusByHenkiloKomoTila(
    "1.2.246.562.24.98743797763",
    peruskouluKomo,
    "KESKEN"
  )

  val linkedOid1: String = MockOppijaNumeroRekisteri.linkedTestPersonOids.head
  val linkedOid2: String = MockOppijaNumeroRekisteri.linkedTestPersonOids(1)

  def postSuoritus(suoritus: String): String = postResourceJson(suoritus, "suoritukset")
  def postArvosana(arvosana: String): String = postResourceJson(arvosana, "arvosanat")

  private def postResourceJson(content: String, endpoint: String): String = {
    post(
      s"/suoritusrekisteri/rest/v1/$endpoint",
      content,
      Map("Content-Type" -> "application/json; charset=UTF-8")
    ) {
      response.status should be(201)
      parse(body).extract[JObject].values("id").asInstanceOf[String]
    }
  }

  def responseIds(body: String): Seq[String] = parse(body)
    .extract[JArray]
    .arr
    .map(_.extract[JObject].values("id").asInstanceOf[String])

  behavior of "SuoritusResource"

  it should "only return Suoritukset modified after muokattuJalkeen" in {
    val before = DateTime.now()
    val aarnenLukioId = postSuoritus(aarnenLukio)
    val after = DateTime.now()
    get("/suoritusrekisteri/rest/v1/suoritukset", ("muokattuJalkeen", before.toString)) {
      response.status should be(200)
      parse(response.body).extract[JArray].arr.length should be(1)
      responseIds(response.body) should contain(aarnenLukioId)
    }
    get("/suoritusrekisteri/rest/v1/suoritukset", ("muokattuJalkeen", after.toString)) {
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
    get("/suoritusrekisteri/rest/v1/suoritukset", ("muokattuJalkeen", before.toString)) {
      response.status should be(200)
      parse(response.body).extract[JArray].arr.length should be(2)
      val ids = responseIds(response.body)
      ids should contain(aarnenLukioId)
      ids should contain(tyynenLukioId)
    }
    get("/suoritusrekisteri/rest/v1/suoritukset", ("muokattuJalkeen", afterAarne.toString)) {
      response.status should be(200)
      parse(response.body).extract[JArray].arr.length should be(1)
      responseIds(response.body) should contain(tyynenLukioId)
    }
    get("/suoritusrekisteri/rest/v1/suoritukset", ("muokattuJalkeen", after.toString)) {
      response.status should be(200)
      parse(response.body).extract[JArray].arr shouldBe empty
    }
  }

  it should "support queries by komo" in {
    val aarnenLukioId = postSuoritus(aarnenLukio)
    val tyynenLukioId = postSuoritus(tyynenLukio)
    val tyynenPeruskouluId = postSuoritus(tyynenPeruskoulu)
    get("/suoritusrekisteri/rest/v1/suoritukset", ("komo", lukioKomo)) {
      parse(response.body).extract[JArray].arr.length should be(2)
      val ids = responseIds(response.body)
      ids should contain(aarnenLukioId)
      ids should contain(tyynenLukioId)
    }
    get("/suoritusrekisteri/rest/v1/suoritukset", ("komo", peruskouluKomo)) {
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
    get(
      "/suoritusrekisteri/rest/v1/suoritukset",
      ("komo", lukioKomo),
      ("muokattuJalkeen", afterAarne.toString)
    ) {
      response.status should be(200)
      parse(response.body).extract[JArray].arr.length should be(2)
      val ids = responseIds(response.body)
      ids should contain(tyynenLukioId)
      ids should contain(aarnenLukioValmistuminenId)
    }
    get(
      "/suoritusrekisteri/rest/v1/suoritukset",
      ("komo", lukioKomo),
      ("muokattuJalkeen", afterTyyne.toString)
    ) {
      response.status should be(200)
      parse(response.body).extract[JArray].arr.length should be(1)
      responseIds(response.body) should contain(aarnenLukioValmistuminenId)
    }
    get(
      "/suoritusrekisteri/rest/v1/suoritukset",
      ("komo", peruskouluKomo),
      ("muokattuJalkeen", afterAarne.toString)
    ) {
      response.status should be(200)
      parse(response.body).extract[JArray].arr.length should be(1)
      responseIds(response.body) should contain(tyynenPeruskouluId)
    }
    get(
      "/suoritusrekisteri/rest/v1/suoritukset",
      ("komo", peruskouluKomo),
      ("muokattuJalkeen", afterTyyne.toString)
    ) {
      response.status should be(200)
      parse(response.body).extract[JArray].arr shouldBe empty
    }
  }

  it should "store ammatillisenKielikoe suoritus so it is returned from oppijat" in {
    get(s"/suoritusrekisteri/rest/v1/oppijat/$aarnenOid") {
      (parse(response.body) \ "suoritukset").extract[JArray].arr shouldBe empty
    }

    val aarnenKielikoe = SuoritusMock.getResourceJson(
      "/mock-data/suoritus/suoritus-aarne-ammatillisen-kielikoe-valmis.json"
    )
    val createdSuoritusResourceId = postSuoritus(aarnenKielikoe)
    postArvosana(s"""{  "suoritus": "$createdSuoritusResourceId", "myonnetty": "19.9.2016",
                        "source": "1.2.246.562.11.00000005429",
                        "arvio": {"arvosana": "hyvaksytty", "asteikko": "HYVAKSYTTY" },
                        "aine": "kielikoe", "lisatieto": "FI"
                     }""")

    get(s"/suoritusrekisteri/rest/v1/oppijat/$aarnenOid") {
      val suoritusJson: JValue = parse(response.body) \ "suoritukset"
      var suoritusArvosanaArray = suoritusJson.extract[JArray].arr
      suoritusArvosanaArray should have length 1

      val kielikoeSuoritusJson = suoritusArvosanaArray.head \ "suoritus"
      val suoritus = kielikoeSuoritusJson.extract[Suoritus]
      suoritus shouldBe an[VirallinenSuoritus]
      val kielikoeSuoritus = suoritus.asInstanceOf[VirallinenSuoritus]

      kielikoeSuoritus.henkiloOid should equal(aarnenOid)
      kielikoeSuoritus.source should equal("Test")
      kielikoeSuoritus.vahvistettu should equal(true)
      kielikoeSuoritus.komo should equal(KomoOids.ammatillisenKielikoe)
      kielikoeSuoritus.myontaja should equal("1.2.246.562.11.00000005429")

      val kielikoeArvosanaArrayJson = suoritusArvosanaArray.head \ "arvosanat"
      val kielikoeArvosanaArray = kielikoeArvosanaArrayJson.extract[JArray].arr
      kielikoeArvosanaArray should have length 1
      val arvosanaJson = kielikoeArvosanaArray.head
      val arvosana = arvosanaJson.extract[Arvosana]

      arvosana.suoritus should equal(UUID.fromString(createdSuoritusResourceId))
      arvosana.arvio should equal(ArvioHyvaksytty("hyvaksytty"))
      arvosana.source should equal("1.2.246.562.11.00000005429")
      arvosana.aine should equal("kielikoe")
      arvosana.lisatieto should equal(Some("FI"))
      arvosana.myonnetty should equal(Some(new LocalDate(2016, 9, 19)))
    }
  }

  it should "Return suoritus resources for all aliases of queried person" in {
    get(s"/suoritusrekisteri/rest/v1/suoritukset?henkilo=$linkedOid1") {
      parse(response.body).extract[JArray].arr shouldBe empty
    }
    get(s"/suoritusrekisteri/rest/v1/suoritukset?henkilo=$linkedOid2") {
      parse(response.body).extract[JArray].arr shouldBe empty
    }

    postSuoritus(
      toJson(
        VirallinenSuoritus(
          komo = "testikomo",
          myontaja = "1.2.3.4",
          tila = "VALMIS",
          valmistuminen = new LocalDate(),
          henkilo = linkedOid1,
          yksilollistaminen = yksilollistaminen.Ei,
          suoritusKieli = "FI",
          opiskeluoikeus = None,
          vahv = false,
          lahde = "lahde"
        )
      )
    )

    get(s"/suoritusrekisteri/rest/v1/suoritukset?henkilo=$linkedOid1") {
      val suoritus: Suoritus with Identified[UUID] = parseSuoritusWithId
      suoritus.henkiloOid should equal(linkedOid1)
    }

    get(s"/suoritusrekisteri/rest/v1/suoritukset?henkilo=$linkedOid2") {
      val suoritus: Suoritus with Identified[UUID] = parseSuoritusWithId
      suoritus.henkiloOid should equal(linkedOid2)
    }
  }

  it should "Update existing Suoritus even with alias person oid" in {
    val originalSuoritus = VirallinenSuoritus(
      komo = "testikomo",
      myontaja = "1.2.3.4",
      tila = "KESKEN",
      valmistuminen = new LocalDate(),
      henkilo = linkedOid1,
      yksilollistaminen = yksilollistaminen.Ei,
      suoritusKieli = "FI",
      opiskeluoikeus = None,
      vahv = false,
      lahde = "lahde"
    )
    postSuoritus(toJson(originalSuoritus))

    get(s"/suoritusrekisteri/rest/v1/suoritukset?henkilo=$linkedOid1") {
      val suoritusWithLinkedOid1: Suoritus with Identified[UUID] = parseSuoritusWithId
      val originalResourceId = suoritusWithLinkedOid1.id
      suoritusWithLinkedOid1.henkiloOid should equal(linkedOid1)

      val suoritusToUpdate =
        suoritusWithLinkedOid1.asInstanceOf[VirallinenSuoritus].copy(tila = "KESKEYTYNYT")
      val updatedSuoritusResourceId = postSuoritus(toJson(suoritusToUpdate))
      updatedSuoritusResourceId should equal(originalResourceId.toString)
    }

    get(s"/suoritusrekisteri/rest/v1/suoritukset?henkilo=$linkedOid2") {
      val suoritusWithLinkedOid2: Suoritus with Identified[UUID] = parseSuoritusWithId
      val originalResourceId = suoritusWithLinkedOid2.id
      suoritusWithLinkedOid2.henkiloOid should equal(linkedOid2)

      val suoritusToUpdate =
        suoritusWithLinkedOid2.asInstanceOf[VirallinenSuoritus].copy(tila = "VALMIS")
      val updatedSuoritusResourceId = postSuoritus(toJson(suoritusToUpdate))
      updatedSuoritusResourceId should equal(originalResourceId.toString)
    }
  }

  private def parseSuoritusWithId: Suoritus with Identified[UUID] = {
    val suoritusArrayJson: JValue = parse(response.body)
    var suoritusArvosanaArray = suoritusArrayJson.extract[JArray].arr
    suoritusArvosanaArray should have length 1

    val suoritusJson = suoritusArvosanaArray.head
    val suoritus = suoritusJson.extract[Suoritus with Identified[UUID]]
    suoritus
  }

  private def toJson(suoritus1: VirallinenSuoritus): String = {
    compact(new SuoritusSerializer().serialize(jsonFormats)(suoritus1))
  }
}
