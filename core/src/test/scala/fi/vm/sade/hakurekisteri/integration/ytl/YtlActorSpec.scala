package fi.vm.sade.hakurekisteri.integration.ytl

import java.nio.charset.Charset
import java.util.UUID

import akka.actor.{ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.MockConfig
import fi.vm.sade.hakurekisteri.arvosana._
import fi.vm.sade.hakurekisteri.integration.DummyActor
import fi.vm.sade.hakurekisteri.integration.henkilo.MockHenkiloActor
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.simple._
import fi.vm.sade.hakurekisteri.rest.support.JDBCJournal
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.suoritus._
import org.h2.tools.RunScript
import org.scalatra.test.scalatest.ScalatraFunSuite

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.slick.lifted.TableQuery

class YtlActorSpec extends ScalatraFunSuite {

  implicit val system = ActorSystem("ytl-integration-test-system")
  implicit val ec = system.dispatcher
  implicit val timeout: Timeout = 60.seconds
  implicit val database = Database.forURL("jdbc:h2:file:data/ytl-integration-test", driver = "org.h2.Driver")

  val config = new MockConfig
  val henkiloActor = system.actorOf(Props(new MockHenkiloActor(config)))
  val suoritusJournal = new JDBCJournal[Suoritus, UUID, SuoritusTable](TableQuery[SuoritusTable])
  val suoritusActor = system.actorOf(Props(new SuoritusActor(journal = suoritusJournal)), "suoritukset")
  val arvosanaJournal = new JDBCJournal[Arvosana, UUID, ArvosanaTable](TableQuery[ArvosanaTable])
  val arvosanaActor = system.actorOf(Props(new ArvosanaActor(journal = arvosanaJournal)), "arvosanat")
  val hakemusActor = system.actorOf(Props(new DummyActor()))
  val actor = system.actorOf(Props(new YtlActor(henkiloActor, suoritusActor, arvosanaActor, hakemusActor, Some(YTLConfig("","","","","",List(),"")))), "ytl")

  private def waitForSuoritus: Future[Suoritus with Identified[UUID]] = {
    Future {
      val suoritusQ = SuoritusQuery(Some("210452-138M"))
      var results: Seq[Suoritus with Identified[UUID]] = List()
      while(results.isEmpty) {
        Thread.sleep(100)
        results = Await.result((suoritusActor ? suoritusQ).mapTo[Seq[Suoritus with Identified[UUID]]], 15.seconds)
      }
      results(0)
    }
  }

  private def waitForArvosanat(): Future[Seq[Arvosana with Identified[UUID]]] = {
    Future {
      val suoritus = Await.result(waitForSuoritus, 15.seconds)
      val arvosanatQ= ArvosanaQuery(Some(suoritus.id))
      var results: Seq[Arvosana with Identified[UUID]] = List()
      while(results.isEmpty) {
        Thread.sleep(100)
        results = Await.result((arvosanaActor ? arvosanatQ).mapTo[Seq[Arvosana with Identified[UUID]]], 15.seconds)
      }
      results
    }
  }

  test("YtlActor should insert arvosanat to database with koetunnus and aineyhdistelmarooli fields") {
    actor ! YtlResult(UUID.randomUUID(), getClass.getResource("/ytl-osakoe-test.xml").getFile)

    val arvosanat = Await.result(waitForArvosanat(), 15.seconds)
    arvosanat.length should equal (27)
    val arvosanaSA = arvosanat.filter(arvosana => {
      arvosana.aine.equals("A") && arvosana.koetunnus.equals(Some("SA"))
    })
    arvosanaSA.length should equal (1)
    arvosanaSA(0).aineyhdistelmarooli should equal (Some("61"))
  }

  override def stop(): Unit = {
    RunScript.execute("jdbc:h2:file:data/ytl-integration-test", "", "", "classpath:clear-h2.sql", Charset.forName("UTF-8"), false)
    system.shutdown()
    system.awaitTermination(60.seconds)
    super.stop()
  }

}
