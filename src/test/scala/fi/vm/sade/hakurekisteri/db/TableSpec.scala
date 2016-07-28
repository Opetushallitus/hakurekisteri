package fi.vm.sade.hakurekisteri.db

import org.joda.time.DateTime
import org.scalatest.{FlatSpec, Matchers}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import slick.jdbc.meta.MTable
import fi.vm.sade.hakurekisteri.batchimport.{BatchState, ImportBatch, ImportBatchTable, ImportStatus}
import fi.vm.sade.hakurekisteri.storage.repository.{Delta, Updated}
import java.util.UUID

import akka.actor.ActorSystem

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.language.existentials

class TableSpec extends FlatSpec with Matchers {
  implicit val system = ActorSystem("test-jdbc")
  implicit val ec: ExecutionContext = system.dispatcher
  behavior of "ImportBatchTable"

  val table = TableQuery[ImportBatchTable]

  println(table.baseTableRow.tableName)

  it should "be able create itself" in {
    val db = getDb

    val tables = Await.result(db.run(
      table.schema.create andThen MTable.getTables(table.baseTableRow.tableName)
    ), 10.seconds)

    db.close()

    tables.size should be(1)
  }

  def getDb: Database = {
    Database.forURL("jdbc:h2:mem:test;DATABASE_TO_UPPER=false", driver = "org.h2.Driver")
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
    val batch = ImportBatch(xml, Some("externalIdToo"), "test", "test", BatchState.READY, ImportStatus(new DateTime(), Some(new DateTime()), Map("foo" -> Set("foo exception")), Some(1), Some(0), Some(1))).identify(UUID.randomUUID())

    val q = table.filter(_.resourceId === batch.id)
    val action = table.schema.create andThen (table += Updated(batch)) andThen q.result

    println(batch.newId, batch.id, batch.externalId)
    val results: Seq[Delta[ImportBatch, UUID]] = Await.result(db.run(action), 10.seconds)
    println("Results: " + results.size)
    val q2 = table.schema.create andThen (table += Updated(batch)) andThen table.map(x=>x.resourceId).result
    val q2r = Await.result(db.run(q2), 120.seconds)
    println(q2r)
    results.head should be(batch)
    db.close()
  }


}