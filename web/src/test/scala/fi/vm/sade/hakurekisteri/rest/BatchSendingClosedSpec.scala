package fi.vm.sade.hakurekisteri.rest

import java.util.UUID

import akka.actor.{ActorSystem, Props}
import com.ning.http.client.AsyncHttpClient
import fi.vm.sade.hakurekisteri.acceptance.tools.{ConfigurationSupport, FakeAuthorizer}
import fi.vm.sade.hakurekisteri.batchimport._
import fi.vm.sade.hakurekisteri.integration._
import fi.vm.sade.hakurekisteri.integration.parametrit.{HttpParameterActor, SendingPeriod, TiedonsiirtoSendingPeriods}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.simple._
import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriJsonSupport, JDBCJournal}
import fi.vm.sade.hakurekisteri.web.batchimport.{ImportBatchResource, TiedonsiirtoOpen}
import fi.vm.sade.hakurekisteri.web.rest.support.{HakurekisteriSwagger, TestSecurity}
import org.json4s.jackson.Serialization._
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import org.scalatra.swagger.Swagger
import org.scalatra.test.scalatest.ScalatraFunSuite
import siirto.{PerustiedotXmlConverter, SchemaDefinition}

import scala.concurrent.ExecutionContext
import scala.xml.Elem

class BatchSendingClosedSpec extends ScalatraFunSuite with MockitoSugar with DispatchSupport with HakurekisteriJsonSupport with ConfigurationSupport {
  implicit val swagger: Swagger = new HakurekisteriSwagger
  implicit val system = ActorSystem("failing-import-batch")
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val security = new TestSecurity

  implicit val database = Database.forURL("jdbc:h2:mem:importbatchtest2;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
  val eraJournal = new JDBCJournal[ImportBatch, UUID, ImportBatchTable](TableQuery[ImportBatchTable])
  val eraRekisteri = system.actorOf(Props(new ImportBatchActor(eraJournal, 5)))
  val authorized = system.actorOf(Props(new FakeAuthorizer(eraRekisteri)))

  def createEndpointMock = {
    val result = mock[Endpoint]

    when(result.request(forUrl("http://localhost/ohjausparametrit-service/api/v1/rest/parametri/tiedonsiirtosendingperiods"))).thenReturn(
      (200,
        List("Content-Type" -> "application/json"),
        write(TiedonsiirtoSendingPeriods(
          arvosanat = SendingPeriod(0, 1),
          perustiedot = SendingPeriod(0, 1)
        )))
    )

    result
  }
  val asyncProvider = new CapturingProvider(createEndpointMock)
  val client = new VirkailijaRestClient(ServiceConfig(serviceUrl = "http://localhost/ohjausparametrit-service"), aClient = Some(new AsyncHttpClient(asyncProvider)))
  val parameterActor = system.actorOf(Props(new HttpParameterActor(client)))

  override def stop(): Unit = {
    system.shutdown()
    system.awaitTermination()
    super.stop()
  }

  object TestSchema extends SchemaDefinition {
    override val schemaLocation: String = "test.xsd"
    override val schema: Elem =
      <xs:schema attributeFormDefault="unqualified"
                 elementFormDefault="qualified"
                 xmlns:xs="http://www.w3.org/2001/XMLSchema">
        <xs:element name="batch">
          <xs:complexType>
            <xs:sequence>
              <xs:element name="identifier" minOccurs="1" maxOccurs="1"/>
              <xs:element name="data" minOccurs="1" maxOccurs="1"/>
            </xs:sequence>
          </xs:complexType>
        </xs:element>
      </xs:schema>
  }

  addServlet(new ImportBatchResource(authorized, parameterActor, config, (foo) => ImportBatchQuery(None, None, None))("identifier", "perustiedot", "data", PerustiedotXmlConverter, TestSchema), "/batch")


  test("create should return 404 not found") {
    val fileData = XmlPart("file.xml", <batch><identifier>foo</identifier><data>foo</data></batch>)

    post("/batch", Map[String, String](), List("data" -> fileData)) {
      response.status should be(404)
    }
  }

  test("update should return 404 not found") {
    val batch = ImportBatch(<batch><identifier>foo</identifier><data>foo</data></batch>, Some("foo"),"test", "Test", BatchState.READY, ImportStatus()).identify(UUID.randomUUID())
    val json = write(batch)
    post(s"/batch/${batch.id}", json) {
      response.status should be(404)
    }
  }

  test("isopen should return false") {
    get("/batch/isopen") {
      import org.json4s.jackson.Serialization.read

      val isopen = read[TiedonsiirtoOpen](response.body)

      isopen.open should be(false)
    }
  }

}
