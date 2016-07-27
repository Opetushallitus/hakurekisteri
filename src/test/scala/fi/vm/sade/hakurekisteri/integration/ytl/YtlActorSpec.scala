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
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import fi.vm.sade.hakurekisteri.rest.support.JDBCJournal
import fi.vm.sade.hakurekisteri.storage.{DeleteResource, Identified}
import fi.vm.sade.hakurekisteri.suoritus._
import org.h2.tools.RunScript
import org.scalatra.test.scalatest.ScalatraFunSuite

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

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
  val actor = system.actorOf(Props(new YtlActor(henkiloActor, suoritusActor, arvosanaActor, hakemusActor, Some(YTLConfig("", "", "", "", "", List(), "")))), "ytl")

  private def waitForSuoritus(henkilo: String): Future[Suoritus with Identified[UUID]] = {
    Future {
      val suoritusQ = SuoritusQuery(henkilo = Some(henkilo))
      var results: Seq[Suoritus with Identified[UUID]] = List()
      while(results.isEmpty) {
        Thread.sleep(100)
        results = Await.result((suoritusActor ? suoritusQ).mapTo[Seq[Suoritus with Identified[UUID]]], 60.seconds)
      }
      results.head
    }
  }

  private def waitForArvosanat(len: Int = 27): Future[Seq[Arvosana with Identified[UUID]]] = {
    Future {
      val suoritus = Await.result(waitForSuoritus("1.2.246.562.24.71944845619"), 60.seconds)
      val arvosanatQ = ArvosanaQuery(Some(suoritus.id))
      var results: Seq[Arvosana with Identified[UUID]] = List()
      while(results.length < len) {
        Thread.sleep(100)
        results = Await.result((arvosanaActor ? arvosanatQ).mapTo[Seq[Arvosana with Identified[UUID]]], 60.seconds)
      }
      results
    }
  }

  test("YtlActor should insert arvosanat to database with koetunnus and aineyhdistelmarooli fields") {
    actor ! YtlResult(UUID.randomUUID(), getClass.getResource("/ytl-osakoe-test.xml").getFile)

    val arvosanat = Await.result(waitForArvosanat(), 60.seconds)
    arvosanat.length should equal (27)
    val arvosanaSA = arvosanat.filter(arvosana => {
      arvosana.aine.equals("A") && arvosana.lahdeArvot.get("koetunnus").contains("SA")
    })
    arvosanaSA.length should equal (1)
    arvosanaSA.head.lahdeArvot.get("aineyhdistelmarooli") should equal (Some("61"))

    Await.result(suoritusActor ? DeleteResource(arvosanaSA.head.suoritus, "test"), 60.seconds)
  }

  test("YtlActor should deduplicate kokeet properly") {
    actor ! YtlResult(UUID.randomUUID(), getClass.getResource("/ytl-koe-test.xml").getFile)

    val arvosanat = Await.result(waitForArvosanat(len = 17), 60.seconds)
    arvosanat.length should be (17)

    actor ! YtlResult(UUID.randomUUID(), getClass.getResource("/ytl-koe-test2.xml").getFile)

    val arvosanat2 = Await.result(waitForArvosanat(len = 19), 60.seconds)
    arvosanat2.length should be (19)

    Await.result(suoritusActor ? DeleteResource(arvosanat.head.suoritus, "test"), 60.seconds)
  }

  override def stop(): Unit = {
    RunScript.execute("jdbc:h2:file:data/ytl-integration-test", "", "", "classpath:clear-h2.sql", Charset.forName("UTF-8"), false)
    Await.result(system.terminate(), 15.seconds)
    database.close()
    super.stop()
  }

}
