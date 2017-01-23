package fi.vm.sade.hakurekisteri.integration

import java.util.UUID
import java.util.concurrent.TimeUnit

import fi.vm.sade.hakurekisteri.CleanSharedTestJettyBeforeEach
import fi.vm.sade.hakurekisteri.arvosana.{Arvio, Arvosana}
import fi.vm.sade.hakurekisteri.integration.henkilo.MockOppijaNumeroRekisteri
import fi.vm.sade.hakurekisteri.integration.mocks.SuoritusMock
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriJsonSupport
import fi.vm.sade.hakurekisteri.tools.ItPostgres
import fi.vm.sade.hakurekisteri.web.rest.support.TestUser
import org.joda.time.LocalDate
import org.json4s.JsonAST.JObject
import org.json4s.jackson.JsonMethods.{compact, parse}
import org.json4s.{Extraction, JArray, JValue}
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class ArvosanaResourceIntegrationSpec extends FlatSpec with CleanSharedTestJettyBeforeEach with BeforeAndAfterAll with Matchers {
  val lukioKomo = "1.2.246.562.5.2013061010184237348007"
  val peruskouluKomo = "1.2.246.562.13.62959769647"
  private val aarnenOid: String = "1.2.246.562.24.71944845619"
  val aarnenLukio = SuoritusMock.getSuoritusByHenkiloKomoTila(aarnenOid, lukioKomo, "KESKEN")
  val tyynenLukio = SuoritusMock.getSuoritusByHenkiloKomoTila("1.2.246.562.24.98743797763", lukioKomo, "KESKEN")
  val aarnenLukioValmistuminen = SuoritusMock.getSuoritusByHenkiloKomoTila(aarnenOid, lukioKomo, "VALMIS")
  val tyynenPeruskoulu = SuoritusMock.getSuoritusByHenkiloKomoTila("1.2.246.562.24.98743797763", peruskouluKomo, "KESKEN")

  val linkedOid1: String = MockOppijaNumeroRekisteri.linkedTestPersonOids.head
  val linkedOid2: String = MockOppijaNumeroRekisteri.linkedTestPersonOids(1)
  implicit val database = Database.forURL(ItPostgres.getEndpointURL)

  def postSuoritus(suoritus: String): String = postResourceJson(suoritus, "suoritukset")
  def postArvosana(arvosana: String): String = postResourceJson(arvosana, "arvosanat")

  private def postResourceJson(content: String, endpoint: String): String = {
    post(s"/suoritusrekisteri/rest/v1/$endpoint", content, Map("Content-Type" -> "application/json; charset=UTF-8")) {
      response.status should be(201)
      parse(body).extract[JObject].values("id").asInstanceOf[String]
    }
  }

  def responseIds(body: String): Seq[String] = parse(body).extract[JArray].arr
    .map(_.extract[JObject].values("id").asInstanceOf[String])

  override def afterAll(): Unit = {
    super.afterAll()
    database.close()
  }

  behavior of "ArvosanaResource"

  it should "allow arbitrary source for ammatillisen kielikoe arvosana" in {
    val aarnenKielikoe = SuoritusMock.getResourceJson("/mock-data/suoritus/suoritus-aarne-ammatillisen-kielikoe-valmis.json")
    val createdSuoritusResourceId = postSuoritus(aarnenKielikoe)
    val kielikoeArvosana = Arvosana(UUID.fromString(createdSuoritusResourceId), Arvio("hyvaksytty", "HYVAKSYTTY"), "kielikoe", Some("FI"),
      valinnainen = false, myonnetty = Some(new LocalDate(2016, 9, 19)), source = "1.2.246.562.11.00000005429", lahdeArvot = Map())
    postArvosana(toJson(kielikoeArvosana))

    get(s"/suoritusrekisteri/rest/v1/oppijat/$aarnenOid") {
      val suoritusJson: JValue = parse(response.body) \ "suoritukset"
      var suoritusArvosanaArray = suoritusJson.extract[JArray].arr
      suoritusArvosanaArray should have length 1

      val kielikoeArvosanaArrayJson = suoritusArvosanaArray.head \ "arvosanat"
      val kielikoeArvosanaArray = kielikoeArvosanaArrayJson.extract[JArray].arr
      kielikoeArvosanaArray should have length 1
      val arvosanaJson = kielikoeArvosanaArray.head
      val arvosana = arvosanaJson.extract[Arvosana]

      arvosana.source should equal(kielikoeArvosana.source)
    }
  }

  it should "allow rekisterinpitaja to update any kind of arvosana" in {
    val createdSuoritusResourceId = postSuoritus(aarnenLukio)
    postArvosana(s"""{ "suoritus": "$createdSuoritusResourceId",
                       "arvio": { "arvosana": "C", "asteikko": "YO", "pisteet": 4},
                       "aine": "AINEREAALI",
                       "valinnainen": false,
                       "lisatieto": "UO",
                       "myonnetty": "21.12.1988"}""")

    Await.result(database.run(DBIO.seq(
      sqlu"update suoritus set source = 'changed suoritus source' where resource_id = ${createdSuoritusResourceId}",
      sqlu"update arvosana set source = 'changed arvosana source' where suoritus = ${createdSuoritusResourceId}")), atMost = Duration(10, TimeUnit.SECONDS))

    postArvosana(s"""{ "suoritus": "$createdSuoritusResourceId",
                       "arvio": { "arvosana": "C", "asteikko": "YO", "pisteet": 5},
                       "aine": "AINEREAALI",
                       "valinnainen": false,
                       "lisatieto": "UO",
                       "myonnetty": "21.12.1988"}""")

    get(s"/suoritusrekisteri/rest/v1/oppijat/$aarnenOid") {
      val suoritusJson: JValue = parse(response.body) \ "suoritukset"
      var suoritusArvosanaArray = suoritusJson.extract[JArray].arr
      suoritusArvosanaArray should have length 1

      val kielikoeArvosanaArrayJson = suoritusArvosanaArray.head \ "arvosanat"
      val kielikoeArvosanaArray = kielikoeArvosanaArrayJson.extract[JArray].arr
      kielikoeArvosanaArray should have length 1
      val arvosanaJson = kielikoeArvosanaArray.head
      val arvosana = arvosanaJson.extract[Arvosana]

      arvosana.source should equal(TestUser.username)
    }
  }

  private def toJson(o: Any): String = {
    val jValue: JValue = Extraction.decompose(o)(HakurekisteriJsonSupport.format)
    compact(jValue)
  }
}
