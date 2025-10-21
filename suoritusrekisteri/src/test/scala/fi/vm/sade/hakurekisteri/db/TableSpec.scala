package fi.vm.sade.hakurekisteri.db

import akka.actor.ActorSystem

import java.util.UUID
import fi.vm.sade.hakurekisteri.batchimport.{
  BatchState,
  ImportBatch,
  ImportBatchTable,
  ImportStatus
}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.storage.repository.{Delta, Updated}
import fi.vm.sade.hakurekisteri.tools.ItPostgres
import org.joda.time.DateTime
import org.postgresql.util.PSQLException
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import slick.jdbc.meta.MTable

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.language.existentials

class TableSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  implicit val system: ActorSystem = ActorSystem("test-jdbc")
  implicit val ec: ExecutionContext = system.dispatcher
  behavior of "ImportBatchTable"

  val table = TableQuery[ImportBatchTable]
  var db: Database = _
  println(table.baseTableRow.tableName)

  override def beforeAll(): Unit = {
    db = ItPostgres.getDatabase
    ItPostgres.reset()
    try {
      println("Creating database")
      Await.result(
        db.run(
          table.schema.create
        ),
        10.seconds
      )
    } catch {
      case e: PSQLException if e.getMessage.contains("already exists") =>
        println("Database already exists")
      case e: Throwable =>
        println(s"Unexpected error: $e")
        throw e
    }
  }

  override def afterAll(): Unit = {
    Await.result(system.terminate(), 15.seconds)
    db.close()
  }

  it should "be able create itself" in {
    val tables: Vector[MTable] = Await.result(
      db.run(
        MTable.getTables(None, None, Some(table.baseTableRow.tableName), None)
      ),
      10.seconds
    )

    tables.size should be(1)
  }

  it should "be able to store updates" in {
    val xml = <batchdata>data</batchdata>
    val batch =
      ImportBatch(xml, Some("externalId"), "test", "test", BatchState.READY, ImportStatus())
        .identify(UUID.randomUUID())

    val result = Await.result(
      db.run(
        table += Updated(batch)
      ),
      10.seconds
    )

    result should be(1)
  }

  it should "be able to retrieve updates" in {
    val xml = <batchdata>data</batchdata>
    val batch: ImportBatch with Identified[UUID] = ImportBatch(
      xml,
      Some("externalIdToo"),
      "test",
      "test",
      BatchState.READY,
      ImportStatus(
        new DateTime(),
        Some(new DateTime()),
        Map("foo" -> Set("foo exception")),
        Some(1),
        Some(0),
        Some(1)
      )
    ).identify(UUID.randomUUID())
    val q = table.filter(_.resourceId === batch.id)

    val action = (table += Updated(batch)) andThen q.result
    val results: Seq[Delta[ImportBatch, UUID]] = Await.result(db.run(action), 60.seconds)
    results.size should be(1)
    results.head match {
      case updated: Updated[ImportBatch, UUID] => updated.current should be(batch)
      case t                                   => fail(s"Unexpected delta type: $t")
    }
  }
}
