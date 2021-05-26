package fi.vm.sade.hakurekisteri.opiskelija

import java.util.UUID

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.MockConfig
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import fi.vm.sade.hakurekisteri.rest.support.{JDBCJournal, Kausi}
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.tools.ItPostgres
import org.joda.time.DateTime
import org.scalatra.test.scalatest.ScalatraFunSuite

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class OpiskelijaActorSpec extends ScalatraFunSuite {
  implicit val timeout: Timeout = 15.seconds
  private val mockConfig: MockConfig = new MockConfig

  val o1 = Opiskelija(
    "oppilaitos1",
    "9",
    "9A",
    "henkilo1",
    new DateTime(2000, 6, 1, 0, 0),
    Some(new DateTime(2000, 7, 1, 0, 0)),
    "test"
  )
  val o2 = Opiskelija(
    "oppilaitos1",
    "9",
    "9B",
    "henkilo2",
    new DateTime(2000, 6, 1, 0, 0),
    Some(new DateTime(2000, 7, 1, 0, 0)),
    "test"
  )
  val o3 = Opiskelija(
    "oppilaitos2",
    "9",
    "9B",
    "henkilo3",
    new DateTime(2000, 6, 1, 0, 0),
    Some(new DateTime(2000, 7, 1, 0, 0)),
    "test"
  )
  val o4 = Opiskelija(
    "oppilaitos2",
    "9",
    "9B",
    "henkilo4",
    new DateTime(2000, 6, 1, 0, 0),
    Some(new DateTime(2000, 9, 1, 0, 0)),
    "test"
  )
  val o5 =
    Opiskelija("oppilaitos2", "9", "9B", "henkilo5", new DateTime(2000, 6, 1, 0, 0), None, "test")
  val o6 = Opiskelija(
    "oppilaitos2",
    "9",
    "9B",
    "henkilo6",
    new DateTime(2001, 6, 1, 0, 0),
    Some(new DateTime(2001, 9, 1, 0, 0)),
    "test"
  )
  val o7 = Opiskelija(
    "oppilaitos2",
    "9",
    "9B",
    "henkilo7",
    new DateTime(2001, 6, 1, 0, 0),
    Some(new DateTime(2001, 7, 1, 0, 0)),
    "test"
  )
  val o7modified = Opiskelija(
    "oppilaitos2",
    "9",
    "9C",
    "henkilo7",
    new DateTime(2001, 6, 1, 0, 0),
    Some(new DateTime(2001, 7, 1, 0, 0)),
    "test"
  )

  def withActor(test: ActorRef => Any) {
    implicit val system = ActorSystem("opiskelija-test-system")
    implicit val database = ItPostgres.getDatabase
    val journal = new JDBCJournal[Opiskelija, UUID, OpiskelijaTable](
      TableQuery[OpiskelijaTable],
      config = mockConfig
    )
    val actor = system.actorOf(Props(new OpiskelijaJDBCActor(journal, 5, mockConfig)))
    try test(actor)
    finally {
      Await.result(system.terminate(), 15.seconds)
      database.close()
    }
  }

  test("OpiskelijaActor should return the same list of opiskelija also after update") {
    withActor { actor =>
      Await.result(Future.sequence(Seq(o1, o2).map(o => actor ? o)), 15.seconds)

      val q = OpiskelijaQuery(oppilaitosOid = Some("oppilaitos1"), vuosi = Some("2000"))

      val result =
        Await.result((actor ? q).mapTo[Seq[Opiskelija with Identified[UUID]]], 15.seconds)
      Await.result(
        (actor ? result.head.copy(luokka = "9C")).mapTo[Opiskelija with Identified[UUID]],
        15.seconds
      )
      val result2 =
        Await.result((actor ? q).mapTo[Seq[Opiskelija with Identified[UUID]]], 15.seconds)

      result.length should be(result2.length)
    }
  }

  test("OpiskelijaActor should filter by henkilo OID") {
    withActor { actor =>
      Await.result(Future.sequence(List(o1, o2).map(actor ? _)), 15.seconds)
      Await.result(actor ? OpiskelijaQuery(henkilo = Some("henkilo1")), 15.seconds) should be(
        Seq(o1)
      )
    }
  }

  test("OpiskelijaActor should filter by oppilaitos OID") {
    withActor { actor =>
      Await.result(Future.sequence(List(o1, o2, o3).map(actor ? _)), 15.seconds)
      Await.result(
        actor ? OpiskelijaQuery(oppilaitosOid = Some("oppilaitos2")),
        15.seconds
      ) should be(Seq(o3))
    }
  }

  test("OpiskelijaActor should filter by luokka") {
    withActor { actor =>
      Await.result(Future.sequence(List(o1, o2, o3).map(actor ? _)), 15.seconds)
      val r = Await.result(
        (actor ? OpiskelijaQuery(luokka = Some("9B"))).mapTo[Seq[Opiskelija with Identified[UUID]]],
        15.seconds
      )
      r should contain(o2)
      r should contain(o3)
    }
  }

  test("OpiskelijaActor should filter by vuosi") {
    withActor { actor =>
      Await.result(Future.sequence(List(o1, o2, o3, o4, o5, o6, o7).map(actor ? _)), 15.seconds)
      val r = Await.result(
        (actor ? OpiskelijaQuery(vuosi = Some("2001")))
          .mapTo[Seq[Opiskelija with Identified[UUID]]],
        15.seconds
      )
      r.length should be(3)
      r should contain(o5)
      r should contain(o6)
      r should contain(o7)
    }
  }

  test("OpiskelijaActor should filter by kausi") {
    withActor { actor =>
      Await.result(Future.sequence(List(o4, o5, o6).map(actor ? _)), 15.seconds)
      val r = Await.result(
        (actor ? OpiskelijaQuery(kausi = Some(Kausi.Syksy)))
          .mapTo[Seq[Opiskelija with Identified[UUID]]],
        15.seconds
      )
      r.length should be(3)
    }
  }

  test("OpiskelijaActor should filter by vuosi and kausi") {
    withActor { actor =>
      Await.result(Future.sequence(List(o1, o2, o3, o4, o5, o6, o7).map(actor ? _)), 15.seconds)
      val r = Await.result(
        (actor ? OpiskelijaQuery(vuosi = Some("2001"), kausi = Some(Kausi.Syksy)))
          .mapTo[Seq[Opiskelija with Identified[UUID]]],
        15.seconds
      )
      r.length should be(2)
      r should contain(o5)
      r should contain(o6)
    }
  }

  test("OpiskelijaActor should filter by paiva") {
    withActor { actor =>
      Await.result(Future.sequence(List(o1, o2, o3, o4, o5, o6).map(actor ? _)), 15.seconds)
      val r = Await.result(
        (actor ? OpiskelijaQuery(paiva = Some(new DateTime(2000, 10, 1, 0, 0))))
          .mapTo[Seq[Opiskelija with Identified[UUID]]],
        15.seconds
      )
      r should be(Seq(o5))
    }
  }

  test("OpiskelijaActor should not return overriden records") {
    withActor { actor =>
      Await.result(Future.sequence(List(o7, o7modified).map(actor ? _)), 15.seconds)
      val r = Await.result(
        (actor ? OpiskelijaQuery(luokka = Some("9C"))).mapTo[Seq[Opiskelija with Identified[UUID]]],
        15.seconds
      )
      val rr = Await.result(
        (actor ? OpiskelijaQuery(luokka = Some("9B"))).mapTo[Seq[Opiskelija with Identified[UUID]]],
        15.seconds
      )
      r should be(Seq(o7modified))
      rr should not contain (o7)
    }
  }
}
