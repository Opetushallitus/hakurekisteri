package fi.vm.sade.hakurekisteri.rest

import java.net.InetAddress
import java.util.UUID
import akka.actor.{ActorSystem, Props}
import akka.testkit.TestActorRef
import fi.vm.sade.hakurekisteri.MockConfig
import fi.vm.sade.hakurekisteri.acceptance.tools.FakeAuthorizer
import fi.vm.sade.hakurekisteri.integration._
import fi.vm.sade.hakurekisteri.integration.henkilo.MockPersonAliasesProvider
import fi.vm.sade.hakurekisteri.integration.koodisto.KoodistoActorRef
import fi.vm.sade.hakurekisteri.integration.parametrit._
import fi.vm.sade.hakurekisteri.koodisto.MockedKoodistoActor
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import fi.vm.sade.hakurekisteri.rest.support.{
  AuditSessionRequest,
  HakurekisteriJsonSupport,
  JDBCJournal,
  Role,
  User
}
import fi.vm.sade.hakurekisteri.storage.repository.Updated
import fi.vm.sade.hakurekisteri.suoritus._
import fi.vm.sade.hakurekisteri.tools.{ItPostgres, Peruskoulu}
import fi.vm.sade.hakurekisteri.web.rest.support._
import fi.vm.sade.hakurekisteri.web.suoritus.SuoritusResource

import javax.servlet.http.HttpServletRequest
import org.joda.time.LocalDate
import org.json4s.jackson.Serialization._
import org.mockito.Mockito._
import org.scalatest.concurrent.Waiters
import org.scalatest.mockito.MockitoSugar
import org.scalatra.test.scalatest.ScalatraFunSuite
import org.springframework.security.cas.authentication.CasAuthenticationToken

import scala.compat.Platform
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.implicitConversions

class SuoritusResourceTestSecurity extends Security {
  object TestUser extends User {
    override def orgsFor(action: String, resource: String): Set[String] = Set(
      "1.2.246.562.10.39644336305"
    )
    override val username: String = "Test"
    override val auditSession =
      AuditSessionRequest(username, Set("1.2.246.562.10.39644336305"), "", "")
    override def casAuthenticationToken: CasAuthenticationToken = {
      fi.vm.sade.hakurekisteri.web.rest.support.TestUser.casAuthenticationToken
    }
    override def hasRole(role: Role) = true
  }
  private def testAuditUser = new fi.vm.sade.auditlog.User(InetAddress.getLocalHost, "-", "-")

  override def currentUser(implicit
    request: HttpServletRequest
  ): Option[fi.vm.sade.hakurekisteri.rest.support.User] = Some(TestUser)
  override def auditUser(implicit request: HttpServletRequest): fi.vm.sade.auditlog.User =
    testAuditUser

}

class SuoritusResourceAdminTestSecurity extends Security {
  object AdminTestUser extends User {
    override def orgsFor(action: String, resource: String): Set[String] = Set(
      "1.2.246.562.10.00000000001"
    )
    override val username: String = "Test"
    override val auditSession =
      AuditSessionRequest(username, Set("1.2.246.562.10.00000000001"), "", "")
    override def casAuthenticationToken: CasAuthenticationToken =
      fi.vm.sade.hakurekisteri.web.rest.support.TestUser.casAuthenticationToken
    override def hasRole(role: Role) = true
  }
  private def testAuditUser =
    new fi.vm.sade.auditlog.User(InetAddress.getByName("123.123.123.1"), "abc123_test", "mockAgent")

  override def currentUser(implicit
    request: HttpServletRequest
  ): Option[fi.vm.sade.hakurekisteri.rest.support.User] = Some(AdminTestUser)
  override def auditUser(implicit request: HttpServletRequest): fi.vm.sade.auditlog.User =
    testAuditUser
}

class SuoritusResourceWithOPHSpec
    extends ScalatraFunSuite
    with MockitoSugar
    with DispatchSupport
    with HakurekisteriJsonSupport
    with Waiters {
  implicit var system: ActorSystem = _
  implicit var database: Database = _
  implicit val swagger = new HakurekisteriSwagger
  implicit val security = new TestSecurity
  private val mockConfig: MockConfig = new MockConfig

  val suoritus = Peruskoulu("1.2.3", "KESKEN", LocalDate.now, "1.2.4")

  def createEndpointMock(periodEnd: Long) = {
    val result = mock[Endpoint]
    when(
      result.request(
        forUrl(
          "http://localhost/ohjausparametrit-service/api/v1/rest/parametri/" + ParameterActor.opoUpdateGraduation
        )
      )
    ).thenReturn(
      (
        200,
        List("Content-Type" -> "application/json"),
        write(
          RestrictionPeriods(
            opoUpdateGraduation = List(SendingPeriod(0, periodEnd))
          )
        )
      )
    )
    result
  }

  val asyncClientNoRestrictions = new CapturingAsyncHttpClient(
    createEndpointMock(Platform.currentTime + (300 * 60 * 1000))
  )
  val asyncClientRestrictionActive = new CapturingAsyncHttpClient(createEndpointMock(0L))

  override def beforeAll(): Unit = {
    system = ActorSystem("test-suoritus-resource")
    database = ItPostgres.getDatabase

    val parameterActor = new ParametritActorRef(
      system.actorOf(Props(new MockParameterActor(config = mockConfig)(system)))
    )
    val koodistoActor = new KoodistoActorRef(system.actorOf(Props(new MockedKoodistoActor())))
    val suoritusJournal =
      new JDBCJournal[Suoritus, UUID, SuoritusTable](TableQuery[SuoritusTable], config = mockConfig)
    suoritusJournal.addModification(Updated(suoritus.identify))
    val suoritusRekisteri = system.actorOf(
      Props(new SuoritusJDBCActor(suoritusJournal, 1, MockPersonAliasesProvider, mockConfig))
    )
    val guardedSuoritusRekisteri = system.actorOf(Props(new FakeAuthorizer(suoritusRekisteri)))

    val servletWithOPHRight =
      new SuoritusResource(guardedSuoritusRekisteri, parameterActor, koodistoActor)
    addServlet(servletWithOPHRight, "/*")

    super.beforeAll()
  }

  override def afterAll(): Unit = {
    try super.afterAll()
    finally {
      Await.result(system.terminate(), 15.seconds)
      database.close()
    }
  }

  test("update should success when no restrictions are in effect") {
    val client = new VirkailijaRestClient(
      ServiceConfig(serviceUrl = "http://localhost/ohjausparametrit-service"),
      aClient = Some(asyncClientNoRestrictions)
    )
    val json =
      ("{\"henkiloOid\":\"1.2.246.562.24.71944845619\",\"source\":\"Test\",\"vahvistettu\":true,\"komo\":\"1.2.246.562.13.62959769647\",\"myontaja\":\"1.2.246.562.10.39644336305\",\"tila\":\"VALMIS\",\"valmistuminen\":\"2016-05-04T21:00:00.000Z\",\"yksilollistaminen\":\"Ei\",\"suoritusKieli\":\"FI\",\"id\":\"22d606f9-b150-44eb-9ad3-60c7a0bffdb8\"}")
    post("/", json) {
      response.status should be(201)
    }
  }

  test("update should success when some restrictions are in effect") {
    val client = new VirkailijaRestClient(
      ServiceConfig(serviceUrl = "http://localhost/ohjausparametrit-service"),
      aClient = Some(asyncClientRestrictionActive)
    )
    val json =
      ("{\"henkiloOid\":\"1.2.246.562.24.71944845619\",\"source\":\"Test\",\"vahvistettu\":true,\"komo\":\"1.2.246.562.13.62959769647\",\"myontaja\":\"1.2.246.562.10.39644336305\",\"tila\":\"VALMIS\",\"valmistuminen\":\"2016-05-04T21:00:00.000Z\",\"yksilollistaminen\":\"Ei\",\"suoritusKieli\":\"FI\",\"id\":\"22d606f9-b150-44eb-9ad3-60c7a0bffdb8\"}")
    post("/", json) {
      response.status should be(201)
    }
  }

  test("should return bad request without query parameters") {
    get("/") {
      response.status should be(400)
      body should include("Vähintään yksi hakuehto on pakollinen")
    }
  }
}

class SuoritusResourceWithOPOSpec
    extends ScalatraFunSuite
    with MockitoSugar
    with DispatchSupport
    with HakurekisteriJsonSupport
    with Waiters {
  implicit var system: ActorSystem = _
  implicit var database: Database = _
  implicit val swagger = new HakurekisteriSwagger
  implicit val security = new SuoritusResourceTestSecurity
  private val mockConfig: MockConfig = new MockConfig

  val suoritus = Peruskoulu("1.2.3", "KESKEN", LocalDate.now, "1.2.4")

  override def beforeAll(): Unit = {
    system = ActorSystem("test-suoritus-resource")
    database = ItPostgres.getDatabase
    val suoritusJournal =
      new JDBCJournal[Suoritus, UUID, SuoritusTable](TableQuery[SuoritusTable], config = mockConfig)
    suoritusJournal.addModification(Updated(suoritus.identify))
    val suoritusRekisteri = system.actorOf(
      Props(new SuoritusJDBCActor(suoritusJournal, 1, MockPersonAliasesProvider, mockConfig))
    )
    val guardedSuoritusRekisteri = system.actorOf(Props(new FakeAuthorizer(suoritusRekisteri)))

    val x = new ParametritActorRef(TestActorRef(new MockParameterActor(true, mockConfig)(system)))
    val y = new ParametritActorRef(TestActorRef(new MockParameterActor(false, mockConfig)(system)))
    val z = new KoodistoActorRef(TestActorRef(new MockedKoodistoActor()))

    val servletWithOPORightActive = new SuoritusResource(guardedSuoritusRekisteri, x, z)
    addServlet(servletWithOPORightActive, "/foo", "foo")

    val servletWithOPORightPassive = new SuoritusResource(guardedSuoritusRekisteri, y, z)
    addServlet(servletWithOPORightPassive, "/bar", "bar")

    super.beforeAll()
  }

  override def afterAll(): Unit = {
    try super.afterAll()
    finally {
      Await.result(system.terminate(), 15.seconds)
      database.close()
    }
  }

  test("update should success when no restrictions are in effect") {
    val json =
      ("{\"henkiloOid\":\"1.2.246.562.24.71944845619\",\"source\":\"Test\",\"vahvistettu\":true,\"komo\":\"1.2.246.562.13.62959769647\",\"myontaja\":\"1.2.246.562.10.39644336305\",\"tila\":\"VALMIS\",\"valmistuminen\":\"2016-05-04T21:00:00.000Z\",\"yksilollistaminen\":\"Ei\",\"suoritusKieli\":\"FI\",\"id\":\"22d606f9-b150-44eb-9ad3-60c7a0bffdb8\"}")
    post("/bar", json) {
      response.status should be(201)
    }
  }

  test("update should fail when some restrictions are in effect") {
    val json =
      ("{\"henkiloOid\":\"1.2.246.562.24.71944845619\",\"source\":\"Test\",\"vahvistettu\":true,\"komo\":\"1.2.246.562.13.62959769647\",\"myontaja\":\"1.2.246.562.10.39644336305\",\"tila\":\"VALMIS\",\"valmistuminen\":\"2016-05-04T21:00:00.000Z\",\"yksilollistaminen\":\"Ei\",\"suoritusKieli\":\"FI\",\"id\":\"22d606f9-b150-44eb-9ad3-60c7a0bffdb8\"}")
    post("/foo", json) {
      response.status should be(404)
    }
  }
}
