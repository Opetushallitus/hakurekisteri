package fi.vm.sade.hakurekisteri.rest

import java.util.UUID

import akka.actor.{ActorSystem, Props}
import fi.vm.sade.hakurekisteri.MockConfig
import fi.vm.sade.hakurekisteri.acceptance.tools.FakeAuthorizer
import fi.vm.sade.hakurekisteri.arvosana._
import fi.vm.sade.hakurekisteri.integration.henkilo.MockPersonAliasesProvider
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriJsonSupport, JDBCJournal}
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.storage.repository.Updated
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, SuoritusJDBCActor, SuoritusTable}
import fi.vm.sade.hakurekisteri.tools.ItPostgres
import fi.vm.sade.hakurekisteri.web.arvosana.{ArvosanaResource, ArvosanaSwaggerApi}
import fi.vm.sade.hakurekisteri.web.rest.support._
import org.joda.time.LocalDate
import org.json4s.jackson.Serialization._
import org.scalatest.BeforeAndAfterEach
import org.scalatra.test.scalatest.ScalatraFunSuite

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.implicitConversions

class ArvosanaSerializeSpec extends ScalatraFunSuite with BeforeAndAfterEach {
  val suoritus = UUID.randomUUID()
  val arvosana1 =
    Arvosana(suoritus, Arvio410("10"), "AI", Some("FI"), valinnainen = false, None, "Test", Map())
  val arvosana12 = Arvosana(
    suoritus,
    Arvio410("10"),
    "AI",
    Some("FI"),
    valinnainen = true,
    None,
    "Test",
    Map(),
    Some(0)
  )
  val arvosana2 = Arvosana(
    UUID.randomUUID(),
    ArvioYo("L", Some(100)),
    "AI",
    Some("FI"),
    valinnainen = false,
    Some(new LocalDate()),
    "Test",
    Map()
  )
  val arvosana3 = Arvosana(
    UUID.randomUUID(),
    ArvioOsakoe("10"),
    "AI",
    Some("FI"),
    valinnainen = false,
    Some(new LocalDate()),
    "Test",
    Map()
  )

  implicit var system: ActorSystem = _
  implicit var database: Database = _
  var arvosanaJournal: JDBCJournal[Arvosana, UUID, ArvosanaTable] = _
  var suoritusJournal: JDBCJournal[Suoritus, UUID, SuoritusTable] = _
  implicit val security = new TestSecurity
  implicit val swagger = new HakurekisteriSwagger
  private val mockConfig: MockConfig = new MockConfig

  override def beforeAll(): Unit = {
    system = ActorSystem()
    database = Database.forURL(ItPostgres.getEndpointURL)
    arvosanaJournal =
      new JDBCJournal[Arvosana, UUID, ArvosanaTable](TableQuery[ArvosanaTable], config = mockConfig)
    suoritusJournal =
      new JDBCJournal[Suoritus, UUID, SuoritusTable](TableQuery[SuoritusTable], config = mockConfig)

    val guardedArvosanaRekisteri = system.actorOf(
      Props(
        new FakeAuthorizer(
          system.actorOf(Props(new ArvosanaJDBCActor(arvosanaJournal, 1, mockConfig)))
        )
      )
    )
    val guardedSuoritusRekisteri = system.actorOf(
      Props(
        new FakeAuthorizer(
          system.actorOf(
            Props(new SuoritusJDBCActor(suoritusJournal, 1, MockPersonAliasesProvider, mockConfig))
          )
        )
      )
    )

    //val guardedSuoritusRekisteri = system.actorOf(Props(new FakeAuthorizer(system.actorOf(Props(new SuoritusJDBCActor(suoritusJournal, 1, mockConfig))))))
    //addServlet(new HakurekisteriResource[Arvosana](guardedArvosanaRekisteri, ArvosanaQuery(_)) with ArvosanaSwaggerApi with HakurekisteriCrudCommands[Arvosana], "/*")
    addServlet(new ArvosanaResource(guardedArvosanaRekisteri, guardedSuoritusRekisteri), "/*")
    //addServlet(new SuoritusResource(guardedSuoritusRekisteri, mockParameterActor, mockKoodistoActor), "/*")

    super.beforeAll()
  }

  override def afterAll(): Unit = {
    try super.afterAll()
    finally {
      Await.result(system.terminate(), 15.seconds)
      database.close()
    }
  }

  override def beforeEach(): Unit = {
    ItPostgres.reset()
    Seq(arvosana1, arvosana12, arvosana2, arvosana3).foreach(a =>
      arvosanaJournal.addModification(Updated(a.identify))
    )
  }

  test("query should return 200") {
    get(s"/?suoritus=${suoritus.toString}") {
      status should equal(200)
    }
  }

  test("body should contain a sequence with two arvosanas") {
    get(s"/?suoritus=${suoritus.toString}") {
      implicit val formats = HakurekisteriJsonSupport.format
      import org.json4s.jackson.Serialization.read

      val arvosanat = read[Seq[Arvosana]](body)

      arvosanat.length should equal(2)
    }
  }

  test("empty query should return 400") {
    get("/") {
      status should be(400)
    }
  }

  test("send arvosana should be successful") {
    implicit val formats = HakurekisteriJsonSupport.format
    val json = write(arvosana12)
    post("/", json, Map("Content-Type" -> "application/json; charset=utf-8")) {
      status should equal(201)
    }
  }

  test("send arvosana should return saved") {
    implicit val formats = HakurekisteriJsonSupport.format
    Seq(arvosana1, arvosana12, arvosana2, arvosana3).foreach(a => {
      post("/", write(a), Map("Content-Type" -> "application/json; charset=utf-8")) {
        val saved = read[Arvosana with Identified[UUID]](body)
        saved.suoritus should equal(a.suoritus)
        saved.arvio should equal(a.arvio)
        saved.aine should equal(a.aine)
        saved.lisatieto should equal(a.lisatieto)
        saved.valinnainen should equal(a.valinnainen)
        saved.myonnetty should equal(a.myonnetty)
        saved.source should equal(a.source)
        saved.jarjestys should equal(a.jarjestys)
      }
    })
  }

  test("send YO arvosana without myonnetty should return bad request") {
    val json = "{\"suoritus\":\"" + UUID
      .randomUUID()
      .toString + "\",\"arvio\":{\"asteikko\":\"YO\",\"arvosana\":\"L\"},\"aine\":\"MA\",\"valinnainen\":false}"
    post("/", json, Map("Content-Type" -> "application/json; charset=utf-8")) {
      status should be(400)
      body should include("myonnetty is required for asteikko YO and OSAKOE")
    }
  }

  test("send valinnainen 4-10 arvosana without jarjestys should return bad request") {
    val json = "{\"suoritus\":\"" + UUID
      .randomUUID()
      .toString + "\",\"arvio\":{\"asteikko\":\"4-10\",\"arvosana\":\"10\"},\"aine\":\"MA\",\"valinnainen\":true}"
    post("/", json, Map("Content-Type" -> "application/json; charset=utf-8")) {
      status should be(400)
      body should include("jarjestys is required for valinnainen arvosana")
    }
  }
}
