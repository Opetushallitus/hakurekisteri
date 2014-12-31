package fi.vm.sade.hakurekisteri.batchimport

import java.util.UUID

import akka.actor.{ActorSystem, Props}
import fi.vm.sade.hakurekisteri.acceptance.tools.{FakeAuthorizer, TestSecurity}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.simple._
import fi.vm.sade.hakurekisteri.rest.support.{HakurekisteriJsonSupport, JDBCJournal, HakurekisteriSwagger}
import fi.vm.sade.hakurekisteri.storage.Identified
import org.json4s.Extraction
import org.json4s.jackson.JsonMethods._
import org.scalatra.swagger.Swagger
import org.scalatra.test.Uploadable
import org.scalatra.test.scalatest.ScalatraFunSuite
import siirto.SchemaDefinition

import scala.xml.Elem


class ImportBatchResourceSpec extends ScalatraFunSuite {
  implicit val swagger: Swagger = new HakurekisteriSwagger
  implicit val system = ActorSystem("test-import-batch")

  implicit val database = Database.forURL("jdbc:h2:mem:importbatchtest;DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
  val eraJournal = new JDBCJournal[ImportBatch, UUID, ImportBatchTable](TableQuery[ImportBatchTable])
  val eraRekisteri = system.actorOf(Props(new ImportBatchActor(eraJournal, 5)))
  val authorized = system.actorOf(Props(new FakeAuthorizer(eraRekisteri)))

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


  addServlet(new ImportBatchResource(authorized, (foo) => ImportBatchQuery(None, None, None))("identifier", "test", "data", TestSchema) with TestSecurity, "/batch")


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

    post("/batch", Map[String, String](), List("data" -> fileData)) {
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
      implicit val formats = HakurekisteriJsonSupport.format

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
      implicit val formats = HakurekisteriJsonSupport.format

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
      implicit val formats = HakurekisteriJsonSupport.format

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


  case class XmlPart(fileName: String, xml:Elem) extends Uploadable {
    override lazy val content: Array[Byte] = xml.toString().getBytes("UTF-8")

    override lazy val contentLength: Long = content.length

    override val contentType: String = "application/xml"
  }
}
