package fi.vm.sade.hakurekisteri.opiskelija

import java.nio.charset.Charset
import java.util.UUID

import akka.actor.{ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.simple._
import fi.vm.sade.hakurekisteri.rest.support.JDBCJournal
import fi.vm.sade.hakurekisteri.storage.Identified
import org.h2.tools.RunScript
import org.joda.time.{DateTime, LocalDate}
import org.scalatra.test.scalatest.ScalatraFunSuite

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class OpiskelijaActorSpec extends ScalatraFunSuite {

  implicit val system = ActorSystem("opiskelija-test-system")
  implicit val ec = system.dispatcher
  implicit val timeout: Timeout = 15.seconds
  implicit val database = Database.forURL("jdbc:h2:file:data/opiskelijatest", driver = "org.h2.Driver")

  val o1 = Opiskelija("foo", "9", "9A", "bar", new DateTime(), Some(new DateTime().plus(30000)), "test")
  val o2 = Opiskelija("foo", "9", "9B", "bar2", new DateTime(), Some(new DateTime().plus(30000)), "test")

  val journal = new JDBCJournal[Opiskelija, UUID, OpiskelijaTable](TableQuery[OpiskelijaTable])
  val actor = system.actorOf(Props(new OpiskelijaActor(journal)))

  test("OpiskelijaActor should return the same list of opiskelija also after update") {

    Await.result(Future.sequence(Seq(o1, o2).map(o => actor ? o)), 15.seconds)

    val q = OpiskelijaQuery(oppilaitosOid = Some("foo"), vuosi = Some(new LocalDate().getYear.toString))

    val result = Await.result((actor ? q).mapTo[Seq[Opiskelija with Identified[UUID]]], 15.seconds)
    val update = Await.result((actor ? result.head.copy(luokka = "9C")).mapTo[Opiskelija with Identified[UUID]], 15.seconds)
    val result2 = Await.result((actor ? q).mapTo[Seq[Opiskelija with Identified[UUID]]], 15.seconds)

    result.length should be (result2.length)
  }

  override def stop(): Unit = {
    RunScript.execute("jdbc:h2:file:data/opiskelijatest", "", "", "classpath:clear-h2.sql", Charset.forName("UTF-8"), false)
    system.shutdown()
    system.awaitTermination(15.seconds)
    super.stop()
  }

}
