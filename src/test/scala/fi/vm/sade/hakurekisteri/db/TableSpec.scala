package fi.vm.sade.hakurekisteri.db

import java.util.UUID

import akka.actor.ActorSystem
import fi.vm.sade.hakurekisteri.batchimport.{BatchState, ImportBatch, ImportBatchTable, ImportStatus}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.storage.repository.{Delta, Updated}
import org.h2.engine.SysProperties
import org.joda.time.DateTime
import org.scalatest.{FlatSpec, Matchers}
import slick.jdbc.meta.MTable

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.language.existentials

class TableSpec extends FlatSpec with Matchers {
  implicit val system = ActorSystem("test-jdbc")
  implicit val ec: ExecutionContext = system.dispatcher
  SysProperties.serializeJavaObject = false
  behavior of "ImportBatchTable"

  val table = TableQuery[ImportBatchTable]

  println(table.baseTableRow.tableName)

  def getDb: Database = {
    Database.forURL("jdbc:h2:mem:test;MODE=PostgreSQL", driver = "org.h2.Driver")
  }

  it should "be able create itself" in {
    val db = getDb
    val tables: Vector[MTable] = Await.result(db.run(
      table.schema.create andThen MTable.getTables(table.baseTableRow.tableName)
    ), 10.seconds)

    db.close()

    tables.size should be(1)
  }

  it should "be able to store updates" in {
    val db = getDb
    val xml = <batchdata>data</batchdata>
    val batch = ImportBatch(xml, Some("externalId"), "test", "test", BatchState.READY, ImportStatus()).identify(UUID.randomUUID())

    val result = Await.result(db.run(
      table.schema.create andThen (table += Updated(batch))
    ), 10.seconds)

    db.close()

    result should be(1)
  }


  it should "be able to retrieve updates" in {
    val db = getDb
    val xml = <batchdata>data</batchdata>
    val batch: ImportBatch with Identified[UUID] = ImportBatch(xml, Some("externalIdToo"), "test", "test", BatchState.READY, ImportStatus(new DateTime(), Some(new DateTime()), Map("foo" -> Set("foo exception")), Some(1), Some(0), Some(1))).identify(UUID.randomUUID())
    val q = table.filter(_.resourceId === batch.id)

    val action =  table.schema.create andThen (table += Updated(batch)) andThen q.result
    val results: Seq[Delta[ImportBatch, UUID]] = Await.result(db.run(action), 60.seconds)
    results.size should be(1)
    val Updated(current) = results.head
    current should be(batch)
    db.close()
  }


}