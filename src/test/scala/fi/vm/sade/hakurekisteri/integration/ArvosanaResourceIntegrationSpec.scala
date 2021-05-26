package fi.vm.sade.hakurekisteri.integration

import java.util.UUID
import java.util.concurrent.TimeUnit

import fi.vm.sade.hakurekisteri.CleanSharedTestJettyBeforeEach
import fi.vm.sade.hakurekisteri.arvosana.{Arvio, Arvosana}
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

class ArvosanaResourceIntegrationSpec
    extends FlatSpec
    with CleanSharedTestJettyBeforeEach
    with BeforeAndAfterAll
    with Matchers {
  val lukioKomo = "1.2.246.562.5.2013061010184237348007"
  private val aarnenOid: String = "1.2.246.562.24.71944845619"
  val aarnenLukio: String =
    SuoritusMock.getSuoritusByHenkiloKomoTila(aarnenOid, lukioKomo, "KESKEN")
  val peruskouluKomo = "1.2.246.562.13.62959769647"
  private val tyynenOid = "1.2.246.562.24.98743797763"
  val tyynenPeruskoulu =
    SuoritusMock.getSuoritusByHenkiloKomoTila(tyynenOid, peruskouluKomo, "KESKEN")

  implicit val database
    : _root_.fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.backend.DatabaseDef =
    Database.forURL(
      ItPostgres.getEndpointURL,
      ItPostgres.container.username,
      ItPostgres.container.password
    )

  def postSuoritus(suoritus: String): String = postResourceJson(suoritus, "suoritukset", 201)
  def postArvosana(
    arvosana: String,
    id: Option[String] = None,
    expectedStatusCode: Int = 201
  ): String =
    postResourceJson(arvosana, "arvosanat" + id.map("/" + _).getOrElse(""), expectedStatusCode)

  private def postResourceJson(
    content: String,
    endpoint: String,
    expectedStatusCode: Int
  ): String = {
    post(
      s"/suoritusrekisteri/rest/v1/$endpoint",
      content,
      Map("Content-Type" -> "application/json; charset=UTF-8")
    ) {
      response.status should be(expectedStatusCode)
      parse(body).extract[JObject].values("id").asInstanceOf[String]
    }
  }

  def responseIds(body: String): Seq[String] = parse(body)
    .extract[JArray]
    .arr
    .map(_.extract[JObject].values("id").asInstanceOf[String])

  override def beforeEach(): Unit = {
    super.beforeEach()
    ItPostgres.reset()
  }

  override def afterAll(): Unit = {
    super.afterAll()
    database.close()
  }

  behavior of "ArvosanaResource"

  it should "allow arbitrary source for ammatillisen kielikoe arvosana" in {
    val aarnenKielikoe = SuoritusMock.getResourceJson(
      "/mock-data/suoritus/suoritus-aarne-ammatillisen-kielikoe-valmis.json"
    )
    val createdSuoritusResourceId = postSuoritus(aarnenKielikoe)
    val kielikoeArvosana = Arvosana(
      UUID.fromString(createdSuoritusResourceId),
      Arvio("hyvaksytty", "HYVAKSYTTY"),
      "kielikoe",
      Some("FI"),
      valinnainen = false,
      myonnetty = Some(new LocalDate(2016, 9, 19)),
      source = "1.2.246.562.11.00000005429",
      lahdeArvot = Map()
    )
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

    Await.result(
      database.run(
        DBIO.seq(
          sqlu"update suoritus set source = 'changed suoritus source' where resource_id = ${createdSuoritusResourceId}",
          sqlu"update arvosana set source = 'changed arvosana source' where suoritus = ${createdSuoritusResourceId}"
        )
      ),
      atMost = Duration(10, TimeUnit.SECONDS)
    )

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

  it should "allow rekisterinpitaja to update any kind of arvosana even with different source" in {
    val createdSuoritusResourceId = postSuoritus(tyynenPeruskoulu)
    val arvosanaResourceId = postArvosana(s"""{ "suoritus": "$createdSuoritusResourceId",
                       "arvio": { "arvosana": "8", "asteikko": "4-10"},
                       "aine": "B1",
                       "valinnainen": false,
                       "lisatieto": "SV",
                       "myonnetty": "21.12.1988"}""")

    postArvosana(
      s"""{ "suoritus": "$createdSuoritusResourceId",
                       "arvio": { "arvosana": "9", "asteikko": "4-10"},
                       "aine": "B1",
                       "valinnainen": false,
                       "lisatieto": "SV",
                       "source": "$tyynenOid",
                       "myonnetty": "21.12.1988"}""",
      Some(arvosanaResourceId),
      200
    )

    get(s"/suoritusrekisteri/rest/v1/oppijat/$tyynenOid") {
      val suoritusJson: JValue = parse(response.body) \ "suoritukset"
      var suoritusArvosanaArray = suoritusJson.extract[JArray].arr
      suoritusArvosanaArray should have length 1

      val peruskouluArvosanat = suoritusArvosanaArray.head \ "arvosanat"
      val peruskouluArvosanaArray = peruskouluArvosanat.extract[JArray].arr
      peruskouluArvosanaArray should have length 1
      val arvosanaJson = peruskouluArvosanaArray.head
      val arvosana = arvosanaJson.extract[Arvosana]

      arvosana.source should equal(tyynenOid)
    }
  }

  private def toJson(o: Any): String = {
    val jValue: JValue = Extraction.decompose(o)(HakurekisteriJsonSupport.format)
    compact(jValue)
  }
}
