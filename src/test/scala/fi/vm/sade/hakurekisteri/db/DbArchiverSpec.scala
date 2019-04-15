package fi.vm.sade.hakurekisteri.db

import java.util.Calendar
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.MockConfig
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import fi.vm.sade.hakurekisteri.tools.ItPostgres
import org.scalatest.concurrent.Waiters
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FlatSpec, Matchers}
import slick.sql.SqlAction
import support.{DbArchiver, DbJournals}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

class DbArchiverSpec extends FlatSpec with BeforeAndAfterEach with BeforeAndAfterAll with Matchers with MockitoSugar with Waiters {

  private implicit val database = Database.forURL(ItPostgres.getEndpointURL)
  private implicit val system = ActorSystem("test-jdbc")
  private implicit val ec: ExecutionContext = system.dispatcher
  private implicit val timeout: Timeout = Timeout(30, TimeUnit.SECONDS)
  private val config: MockConfig = new MockConfig
  private val journals: DbJournals = new DbJournals(config)

  override protected def beforeEach(): Unit = {
    ItPostgres.reset()
  }

  override protected def afterAll(): Unit = {
    Await.result(system.terminate(), 15.seconds)
    database.close()
  }

  private def run[T](f: Future[T]): T = Await.result(f, atMost = timeout.duration)

  private case class TestData(daysInThePast: Int, current: Boolean)

  private def insertTestRecords(testData: List[TestData]) = {
    def getEpoch(daysInThePast: Int): Long = {
      val day = Calendar.getInstance
      day.add(Calendar.DATE, - daysInThePast)
      day.getTime.getTime
    }
    testData.foreach(t => {
      var insertSql: SqlAction[Int, NoStream, Effect] = sqlu"""
          insert into opiskelija
            (resource_id, oppilaitos_oid, luokkataso, luokka, henkilo_oid, alku_paiva, loppu_paiva, inserted, deleted, source, current)
          values ('44976e21-89b5-47b9-9b0e-ad834127691d', '1.2.246.562.10.15514292604', '9', '9A', '1.2.246.562.24.80710434876',
                  '1533675600000', '1553205600000', '#${getEpoch(t.daysInThePast)}', 'false', 'koski', '#${t.current}')"""
      run(database.run(insertSql))
    })
  }

  it should "archive the old-enough non-current record" in {
    val dbArchiver = new DbArchiver(config)

    insertTestRecords(List(TestData(10, true), TestData(config.archiveNonCurrentAfterDays.toInt + 1, false)))

    val result1 = run(database.run(sql"select count(*) from opiskelija".as[String]))
    result1.head.toInt should be(2)

    (dbArchiver.archive())()

    val result2 = run(database.run(sql"select count(*) from a_opiskelija".as[String]))
    result2.head.toInt should be(1)
  }

  it should "not archive non-current record if not old enough" in {
    val dbArchiver = new DbArchiver(config)

    insertTestRecords(List(TestData(10, true), TestData(config.archiveNonCurrentAfterDays.toInt - 1, false)))

    val result1 = run(database.run(sql"select count(*) from opiskelija".as[String]))
    result1.head.toInt should be(2)

    (dbArchiver.archive())()

    val result2 = run(database.run(sql"select count(*) from a_opiskelija".as[String]))
    result2.head.toInt should be(2)
  }
}
