package fi.vm.sade.hakurekisteri.rest

import java.util.UUID
import javax.servlet.http.HttpServletRequest

import akka.actor.{Props, ActorSystem}
import akka.testkit.TestActorRef
import com.ning.http.client.AsyncHttpClient
import fi.vm.sade.hakurekisteri.acceptance.tools.FakeAuthorizer
import fi.vm.sade.hakurekisteri.integration.parametrit._
import fi.vm.sade.hakurekisteri.integration._
import fi.vm.sade.hakurekisteri.rest.support.{User, HakurekisteriJsonSupport}
import fi.vm.sade.hakurekisteri.storage.repository.{Updated, InMemJournal}
import fi.vm.sade.hakurekisteri.suoritus.{Suoritus, SuoritusQuery, SuoritusActor}
import fi.vm.sade.hakurekisteri.tools.Peruskoulu
import fi.vm.sade.hakurekisteri.web.rest.support._
import fi.vm.sade.hakurekisteri.web.suoritus.{CreateSuoritusCommand, SuoritusSwaggerApi, SuoritusResource}
import org.joda.time.LocalDate
import org.json4s.jackson.Serialization._
import org.mockito.Mockito._
import org.scalatest.concurrent.AsyncAssertions
import org.scalatest.mock.MockitoSugar
import org.scalatra.test.scalatest.ScalatraFunSuite
import scala.concurrent.duration._

import scala.compat.Platform
import scala.concurrent.{Await, ExecutionContext}
import scala.language.implicitConversions

class SuoritusResourceTestSecurity extends Security {
  object TestUser extends User {
    override def orgsFor(action: String, resource: String): Set[String] = Set("1.2.246.562.10.39644336305")
    override val username: String = "Test"
  }

  override def currentUser(implicit request: HttpServletRequest): Option[fi.vm.sade.hakurekisteri.rest.support.User] = Some(TestUser)
}

class SuoritusResourceWithOPHSpec extends ScalatraFunSuite with MockitoSugar with DispatchSupport with HakurekisteriJsonSupport with AsyncAssertions {
  implicit val system = ActorSystem("test-suoritus-resource")
  implicit val ec: ExecutionContext = system.dispatcher
  implicit def seq2journal[R <: fi.vm.sade.hakurekisteri.rest.support.Resource[UUID, R]](s:Seq[R]): InMemJournal[R, UUID] = {
    val journal = new InMemJournal[R, UUID]
    s.foreach((resource:R) => journal.addModification(Updated(resource.identify(UUID.randomUUID()))))
    journal
  }
  implicit val swagger = new HakurekisteriSwagger
  implicit val security = new TestSecurity


  val suoritus = Peruskoulu("1.2.3", "KESKEN", LocalDate.now,"1.2.4")



  def createEndpointMock(periodEnd: Long) = {
    val result = mock[Endpoint]

    when(result.request(forUrl("http://localhost/ohjausparametrit-service/api/v1/rest/parametri/" + ParameterActor.opoUpdateGraduation))).thenReturn(
      (200,
        List("Content-Type" -> "application/json"),
        write(RestrictionPeriods(
          opoUpdateGraduation = List(SendingPeriod(0, periodEnd))
        )))
    )

    result
  }

  val asyncProviderNoRestrictions = new CapturingProvider(createEndpointMock(Platform.currentTime + (300 * 60 * 1000)))
  val asyncProviderRestrictionActive = new CapturingProvider(createEndpointMock(0L))
  val parameterActor = system.actorOf(Props(new MockParameterActor()))
  val suoritusRekisteri = system.actorOf(Props(new SuoritusActor(Seq(suoritus))))
  val guardedSuoritusRekisteri = system.actorOf(Props(new FakeAuthorizer(suoritusRekisteri)))



  override def stop(): Unit = {
    system.shutdown()
    system.awaitTermination(15.seconds)
    super.stop()
  }

  private val servletWithOPHRight = new SuoritusResource(guardedSuoritusRekisteri, parameterActor, SuoritusQuery(_))
  addServlet(servletWithOPHRight, "/*")

  test("update should success when no restrictions are in effect") {
    val client = new VirkailijaRestClient(ServiceConfig(serviceUrl = "http://localhost/ohjausparametrit-service"), aClient = Some(new AsyncHttpClient(asyncProviderNoRestrictions)))
    val json = ("{\"henkiloOid\":\"1.2.246.562.24.71944845619\",\"source\":\"Test\",\"vahvistettu\":true,\"komo\":\"1.2.246.562.13.62959769647\",\"myontaja\":\"1.2.246.562.10.39644336305\",\"tila\":\"VALMIS\",\"valmistuminen\":\"2016-05-04T21:00:00.000Z\",\"yksilollistaminen\":\"Ei\",\"suoritusKieli\":\"FI\",\"id\":\"22d606f9-b150-44eb-9ad3-60c7a0bffdb8\"}")
    post("/", json) {
      response.status should be(201)
    }
  }

  test("update should success when some restrictions are in effect") {
    val client = new VirkailijaRestClient(ServiceConfig(serviceUrl = "http://localhost/ohjausparametrit-service"), aClient = Some(new AsyncHttpClient(asyncProviderRestrictionActive)))
    val json = ("{\"henkiloOid\":\"1.2.246.562.24.71944845619\",\"source\":\"Test\",\"vahvistettu\":true,\"komo\":\"1.2.246.562.13.62959769647\",\"myontaja\":\"1.2.246.562.10.39644336305\",\"tila\":\"VALMIS\",\"valmistuminen\":\"2016-05-04T21:00:00.000Z\",\"yksilollistaminen\":\"Ei\",\"suoritusKieli\":\"FI\",\"id\":\"22d606f9-b150-44eb-9ad3-60c7a0bffdb8\"}")
    post("/", json) {
      response.status should be(201)
    }
  }
}

class SuoritusResourceWithOPOSpec extends ScalatraFunSuite with MockitoSugar with DispatchSupport with HakurekisteriJsonSupport with AsyncAssertions {
  implicit val system = ActorSystem("test-suoritus-resource")
  implicit val ec: ExecutionContext = system.dispatcher
  implicit def seq2journal[R <: fi.vm.sade.hakurekisteri.rest.support.Resource[UUID, R]](s:Seq[R]): InMemJournal[R, UUID] = {
    val journal = new InMemJournal[R, UUID]
    s.foreach((resource:R) => journal.addModification(Updated(resource.identify(UUID.randomUUID()))))
    journal
  }
  implicit val swagger = new HakurekisteriSwagger
  implicit val security = new SuoritusResourceTestSecurity

  val suoritus = Peruskoulu("1.2.3", "KESKEN", LocalDate.now,"1.2.4")

  val suoritusRekisteri = system.actorOf(Props(new SuoritusActor(Seq(suoritus))))
  val guardedSuoritusRekisteri = system.actorOf(Props(new FakeAuthorizer(suoritusRekisteri)))


  override def stop(): Unit = {
    system.shutdown()
    system.awaitTermination(15.seconds)
    super.stop()
  }

  val x = TestActorRef(new MockParameterActor(true))
  val y = TestActorRef(new MockParameterActor(false))

  private val servletWithOPORightActive = new SuoritusResource(guardedSuoritusRekisteri, x, SuoritusQuery(_))
  addServlet(servletWithOPORightActive, "/foo", "foo")

  private val servletWithOPORightPassive = new SuoritusResource(guardedSuoritusRekisteri, y, SuoritusQuery(_))
  addServlet(servletWithOPORightPassive, "/bar", "bar")

  test("update should success when no restrictions are in effect") {
    val json = ("{\"henkiloOid\":\"1.2.246.562.24.71944845619\",\"source\":\"Test\",\"vahvistettu\":true,\"komo\":\"1.2.246.562.13.62959769647\",\"myontaja\":\"1.2.246.562.10.39644336305\",\"tila\":\"VALMIS\",\"valmistuminen\":\"2016-05-04T21:00:00.000Z\",\"yksilollistaminen\":\"Ei\",\"suoritusKieli\":\"FI\",\"id\":\"22d606f9-b150-44eb-9ad3-60c7a0bffdb8\"}")
    post("/bar", json) {
      response.status should be(201)
    }
  }

  test("update should fail when some restrictions are in effect") {
    val json = ("{\"henkiloOid\":\"1.2.246.562.24.71944845619\",\"source\":\"Test\",\"vahvistettu\":true,\"komo\":\"1.2.246.562.13.62959769647\",\"myontaja\":\"1.2.246.562.10.39644336305\",\"tila\":\"VALMIS\",\"valmistuminen\":\"2016-05-04T21:00:00.000Z\",\"yksilollistaminen\":\"Ei\",\"suoritusKieli\":\"FI\",\"id\":\"22d606f9-b150-44eb-9ad3-60c7a0bffdb8\"}")
    post("/foo", json) {
      response.status should be(500)
    }
  }
}