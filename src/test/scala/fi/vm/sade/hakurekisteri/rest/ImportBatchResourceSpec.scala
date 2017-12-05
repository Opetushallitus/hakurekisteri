package fi.vm.sade.hakurekisteri.rest

import java.util.UUID

import akka.actor.{ActorSystem, Props}
import com.ning.http.client.AsyncHttpClient
import fi.vm.sade.hakurekisteri.MockConfig
import fi.vm.sade.hakurekisteri.acceptance.tools.FakeAuthorizer
import fi.vm.sade.hakurekisteri.batchimport._
import fi.vm.sade.hakurekisteri.integration._
import fi.vm.sade.hakurekisteri.integration.organisaatio.MockOrganisaatioActor
import fi.vm.sade.hakurekisteri.integration.parametrit._
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import fi.vm.sade.hakurekisteri.rest.support._
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.tools.ItPostgres
import fi.vm.sade.hakurekisteri.web.batchimport.{ImportBatchResource, TiedonsiirtoOpen}
import fi.vm.sade.hakurekisteri.web.rest.support.{HakurekisteriSwagger, TestSecurity}
import org.json4s.Extraction
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization._
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.mock.MockitoSugar
import org.scalatra.swagger.Swagger
import org.scalatra.test.Uploadable
import org.scalatra.test.scalatest.ScalatraFunSuite
import siirto.{PerustiedotXmlConverter, SchemaDefinition}

import scala.compat.Platform
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._
import scala.xml.Elem


class ImportBatchResourceSpec extends ScalatraFunSuite with MockitoSugar with DispatchSupport with HakurekisteriJsonSupport with BeforeAndAfterAll {
  implicit val swagger: Swagger = new HakurekisteriSwagger
  implicit val system = ActorSystem("test-import-batch")
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val security = new TestSecurity

  implicit var database: Database = _


  override def beforeAll(): Unit = {
    database = Database.forURL(ItPostgres.getEndpointURL)
    val eraJournal = new JDBCJournal[ImportBatch, UUID, ImportBatchTable](TableQuery[ImportBatchTable])
    val eraOrgRekisteri = system.actorOf(Props(new ImportBatchOrgActor(database)))
    val eraRekisteri = system.actorOf(Props(new ImportBatchActor(eraJournal, 5)))
    val authorized = system.actorOf(Props(new FakeAuthorizer(eraRekisteri)))
    addServlet(new ImportBatchResource(eraOrgRekisteri, authorized, orgsActor, parameterActor, new MockConfig, (foo) => ImportBatchQuery(None, None, None))("identifier", ImportBatch.batchTypePerustiedot, "data", PerustiedotXmlConverter, TestSchema), "/batch")
    super.beforeAll()
  }

  override def afterAll(): Unit = {
    try super.afterAll()
    finally {
      Await.result(system.terminate(), 15.seconds)
      database.close()
    }
  }

  def createEndpointMock = {
    val result = mock[Endpoint]
    val inFuture = Platform.currentTime + (300 * 60 * 1000)

    when(result.request(forUrl("http://localhost/ohjausparametrit-service/api/v1/rest/parametri/tiedonsiirtosendingperiods"))).thenReturn(
      (200,
        List("Content-Type" -> "application/json"),
        write(TiedonsiirtoSendingPeriods(
          arvosanat = SendingPeriod(0, inFuture),
          perustiedot = SendingPeriod(0, inFuture)
        )))
    )

    result
  }
  val asyncProvider = new CapturingProvider(createEndpointMock)
  val client = new VirkailijaRestClient(ServiceConfig(serviceUrl = "http://localhost/ohjausparametrit-service"), aClient = Some(new AsyncHttpClient(asyncProvider)))
  val parameterActor = system.actorOf(Props(new MockParameterActor()(system)))
  val orgsActor = system.actorOf(Props(new MockOrganisaatioActor(new MockConfig())))

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



  test("post should return 201 created") {
    post("/batch", "<batch><identifier>foo</identifier><data>foo</data></batch>") {
      response.status should be(201)
    }
  }

  test("post with fileupload should return 201 created") {
    val fileData = XmlPart("test.xml", <batch><identifier>foo2</identifier><data>foo2</data></batch>)

    post("/batch", Map[String, String](), List("data" -> fileData)) {
      response.status should be(201)
    }
  }

  test("post with bad file should return 400") {
    val fileData = XmlPart("test.xml", <batch><bata>foo</bata></batch>)

    post("/batch", Map[String, String]("Content-Type" -> "multipart/form-data"), List("data" -> fileData)) {
      response.status should be(400)
    }
  }

  test("resource should deduplicate with the same data from the same sender") {
    val data = "<batch><identifier>foo3</identifier><data>foo</data></batch>"

    post("/batch", data) {
      val location: String = response.header("Location")

      post("/batch", data) {
        val secondLocation: String = response.header("Location")
        secondLocation should be(location)
      }
    }
  }

  test("batch should be updated when using the same identifier") {
    post("/batch", "<batch><identifier>foo4</identifier><data>foo</data></batch>") {
      val location = response.header("Location")

      post("/batch", "<batch><identifier>foo4</identifier><data>foo2</data></batch>") {
        val secondLocation = response.header("Location")
        secondLocation should be(location)
        response.body should include("foo2")
      }
    }
  }

  test("get withoutdata should contain the posted batch") {
    post("/batch", "<batch><identifier>foo5</identifier><data>foo</data></batch>") {
      import org.json4s.jackson.Serialization.read
      val batch = read[ImportBatch with Identified[UUID]](response.body)

      get("/batch/withoutdata") {
        val batches = read[Seq[ImportBatch with Identified[UUID]]](response.body)
        batches.map(_.id) should contain(batch.id)
      }
    }
  }

  test("post reprocess should fail on batch already in READY state") {
    post("/batch", "<batch><identifier>foo5</identifier><data>foo</data></batch>") {
      import org.json4s.jackson.Serialization.read

      val batch = read[ImportBatch with Identified[UUID]](response.body)

      post(s"/batch/reprocess/${batch.id}") {
        response.status should be(400)
      }
    }
  }

  test("post reprocess should fail on non-existing batch id") {
    post(s"/batch/reprocess/${UUID.randomUUID()}") {
      response.status should be(404)
    }
  }

  test("post reprocess should set batch to a READY state") {
    post("/batch", "<batch><identifier>foo5</identifier><data>foo</data></batch>") {
      import org.json4s.jackson.Serialization.read

      val batch = read[ImportBatch with Identified[UUID]](response.body)

      post(s"/batch/${batch.id}", compact(Extraction.decompose(batch.copy(state = BatchState.DONE).identify(batch.id)))) {
        post(s"/batch/reprocess/${batch.id}") {
          get(s"/batch/${batch.id}") {
            val ready = read[ImportBatch with Identified[UUID]](response.body)
            ready.state should be(BatchState.READY)
          }
        }
      }
    }
  }

  test("isopen should return true") {
    get("/batch/isopen") {
      import org.json4s.jackson.Serialization.read

      val isopen = read[TiedonsiirtoOpen](response.body)

      isopen.open should be(true)
    }
  }

}

case class XmlPart(fileName: String, xml:Elem) extends Uploadable {
  override lazy val content: Array[Byte] = xml.toString().getBytes("UTF-8")

  override lazy val contentLength: Long = content.length

  override val contentType: String = "application/xml"
}
