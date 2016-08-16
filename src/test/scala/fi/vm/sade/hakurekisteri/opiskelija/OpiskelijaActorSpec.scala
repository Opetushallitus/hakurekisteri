package fi.vm.sade.hakurekisteri.opiskelija

import java.util.UUID

import akka.actor.{ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import fi.vm.sade.hakurekisteri.rest.support.{JDBCJournal, Kausi}
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.tools.ItPostgres
import fi.vm.sade.utils.tcp.ChooseFreePort
import org.h2.engine.SysProperties
import org.joda.time.DateTime
import org.scalatra.test.scalatest.ScalatraFunSuite

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class OpiskelijaActorSpec extends ScalatraFunSuite {

  val portChooser = new ChooseFreePort
  val itDb = new ItPostgres(portChooser)
  itDb.start()
  implicit val system = ActorSystem("opiskelija-test-system")
  implicit val ec = system.dispatcher
  implicit val timeout: Timeout = 15.seconds
  SysProperties.serializeJavaObject = false
  implicit val database = Database.forURL(s"jdbc:postgresql://localhost:${portChooser.chosenPort}/suoritusrekisteri", "postgres")

  val o1 = Opiskelija("oppilaitos1", "9", "9A", "henkilo1", new DateTime(2000, 6, 1, 0, 0), Some(new DateTime(2000, 7, 1, 0, 0)), "test")
  val o2 = Opiskelija("oppilaitos1", "9", "9B", "henkilo2", new DateTime(2000, 6, 1, 0, 0), Some(new DateTime(2000, 7, 1, 0, 0)), "test")
  val o3 = Opiskelija("oppilaitos2", "9", "9B", "henkilo3", new DateTime(2000, 6, 1, 0, 0), Some(new DateTime(2000, 7, 1, 0, 0)), "test")
  val o4 = Opiskelija("oppilaitos2", "9", "9B", "henkilo4", new DateTime(2000, 6, 1, 0, 0), Some(new DateTime(2000, 9, 1, 0, 0)), "test")
  val o5 = Opiskelija("oppilaitos2", "9", "9B", "henkilo5", new DateTime(2000, 6, 1, 0, 0), None, "test")
  val o6 = Opiskelija("oppilaitos2", "9", "9B", "henkilo6", new DateTime(2001, 6, 1, 0, 0), Some(new DateTime(2001, 9, 1, 0, 0)), "test")
  val o7 = Opiskelija("oppilaitos2", "9", "9B", "henkilo7", new DateTime(2001, 6, 1, 0, 0), Some(new DateTime(2001, 7, 1, 0, 0)), "test")

  val journal = new JDBCJournal[Opiskelija, UUID, OpiskelijaTable](TableQuery[OpiskelijaTable])
  val actor = system.actorOf(Props(new OpiskelijaJDBCActor(journal, 5)))

  test("OpiskelijaActor should return the same list of opiskelija also after update") {

    Await.result(Future.sequence(Seq(o1, o2).map(o => actor ? o)), 15.seconds)

    val q = OpiskelijaQuery(oppilaitosOid = Some("oppilaitos1"), vuosi = Some("2000"))

    val result = Await.result((actor ? q).mapTo[Seq[Opiskelija with Identified[UUID]]], 15.seconds)
    Await.result((actor ? result.head.copy(luokka = "9C")).mapTo[Opiskelija with Identified[UUID]], 15.seconds)
    val result2 = Await.result((actor ? q).mapTo[Seq[Opiskelija with Identified[UUID]]], 15.seconds)

    result.length should be (result2.length)
  }

  test("OpiskelijaActor should filter by henkilo OID") {
    Await.result(Future.sequence(List(o1, o2).map(actor ? _)), 15.seconds)
    Await.result(actor ? OpiskelijaQuery(henkilo = Some("henkilo1")), 15.seconds) should be (Seq(o1))
  }

  test("OpiskelijaActor should filter by oppilaitos OID") {
    Await.result(Future.sequence(List(o1, o2, o3).map(actor ? _)), 15.seconds)
    Await.result(actor ? OpiskelijaQuery(oppilaitosOid = Some("oppilaitos2")), 15.seconds) should be (Seq(o3))
  }

  test("OpiskelijaActor should filter by luokka") {
    Await.result(Future.sequence(List(o1, o2, o3).map(actor ? _)), 15.seconds)
    Await.result(actor ? OpiskelijaQuery(luokka = Some("9B")), 15.seconds) should be (Seq(o2, o3))
  }

  test("OpiskelijaActor should filter by vuosi") {
    Await.result(Future.sequence(List(o1, o2, o3, o4, o5, o6, o7).map(actor ? _)), 15.seconds)
    val r = Await.result((actor ? OpiskelijaQuery(vuosi = Some("2001"))).mapTo[Seq[Opiskelija with Identified[UUID]]], 15.seconds)
    r.length should be(3)
    r should contain(o5)
    r should contain(o6)
    r should contain(o7)
  }

  test("OpiskelijaActor should filter by kausi") {
    Await.result(Future.sequence(List(o4, o5, o6).map(actor ? _)), 15.seconds)
    val r = Await.result((actor ? OpiskelijaQuery(kausi = Some(Kausi.Syksy))).mapTo[Seq[Opiskelija with Identified[UUID]]], 15.seconds)
    r.length should be(3)
  }

  test("OpiskelijaActor should filter by vuosi and kausi") {
    Await.result(Future.sequence(List(o1, o2, o3, o4, o5, o6, o7).map(actor ? _)), 15.seconds)
    val r = Await.result((actor ? OpiskelijaQuery(vuosi = Some("2001"), kausi = Some(Kausi.Syksy))).mapTo[Seq[Opiskelija with Identified[UUID]]], 15.seconds)
    r.length should be(2)
    r should contain(o5)
    r should contain(o6)
  }

  test("OpiskelijaActor should filter by paiva") {
    Await.result(Future.sequence(List(o1, o2, o3, o4, o5, o6).map(actor ? _)), 15.seconds)
    val r = Await.result((actor ? OpiskelijaQuery(paiva = Some(new DateTime(2000, 10, 1, 0, 0)))).mapTo[Seq[Opiskelija with Identified[UUID]]], 15.seconds)
    r should be(Seq(o5))
  }

  override def afterAll(): Unit = {
    Await.result(system.terminate(), 15.seconds)
    database.close()
    itDb.stop()
    super.afterAll()
  }

}
