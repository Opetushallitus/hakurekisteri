package fi.vm.sade.hakurekisteri.db

import java.util.UUID
import akka.actor.ActorSystem
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
import org.scalatest.{BeforeAndAfterAll}

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import slick.jdbc.meta.MTable

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.language.existentials

class TableSpec extends AnyFlatSpec with Matchers with BeforeAndAfterAll {
  implicit val system = ActorSystem("test-jdbc")
  implicit val ec: ExecutionContext = system.dispatcher
  behavior of "ImportBatchTable"

  val table = TableQuery[ImportBatchTable]
  implicit var db: Database = _
  println(table.baseTableRow.tableName)

  override def beforeAll(): Unit = {
    db = ItPostgres.getDatabase
    try {
      Await.result(
        db.run(
          table.schema.create
        ),
        10.seconds
      )
    } catch {
      case e: PSQLException => println("Database already exists")
    }
  }

  override def afterAll(): Unit = {
    try super.afterAll()
    finally {
      Await.result(system.terminate(), 15.seconds)
      db.close()
    }
  }

  it should "be able create itself" in {
    ItPostgres.reset()
    val tables: Vector[MTable] = Await.result(
      db.run(
        MTable.getTables(table.baseTableRow.tableName)
      ),
      10.seconds
    )

    tables.size should be(1)
  }

  it should "be able to store updates" in {
    ItPostgres.reset()
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
    ItPostgres.reset()
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
    val Updated(current) = results.head
    current should be(batch)
  }

}
