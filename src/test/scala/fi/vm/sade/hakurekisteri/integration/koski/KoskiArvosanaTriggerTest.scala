package fi.vm.sade.hakurekisteri.integration.koski

import akka.actor.{Actor, ActorSystem, Props}
import akka.testkit.{CallingThreadDispatcher, TestActors}
import fi.vm.sade.hakurekisteri.integration.henkilo.PersonOidsWithAliases
import fi.vm.sade.hakurekisteri.integration.koski.KoskiArvosanaTrigger.SuoritusArvosanat
import fi.vm.sade.hakurekisteri.suoritus._
import org.joda.time.LocalDate
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class KoskiArvosanaTriggerTest extends FlatSpec with Matchers with MockitoSugar {

  implicit val formats = org.json4s.DefaultFormats

  private val jsonDir = "src/test/scala/fi/vm/sade/hakurekisteri/integration/koski/json/"

  it should "parse a koski henkilo" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "testikiira.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    assert(henkilo != null)
    val result: Seq[SuoritusArvosanat] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo)
    val suoritusA = result.head
    val suoritusB = result(1)

    val expectedDate = new LocalDate(2017,8,1)
    suoritusB.lasnadate should equal (expectedDate)
    val oidsWithAliases = PersonOidsWithAliases(Set("1.2.246.562.24.71123947024"), Map.empty)
/*
    TODO fix actor threading problem
    val system = ActorSystem("MySpec")
    val a = system.actorOf(Props(new TestSureActor()).withDispatcher(CallingThreadDispatcher.Id))

    KoskiArvosanaTrigger.muodostaKoskiSuorituksetJaArvosanat(henkilo, a,
      system.actorOf(TestActors.blackholeProps.withDispatcher(CallingThreadDispatcher.Id)),
      system.actorOf(TestActors.blackholeProps.withDispatcher(CallingThreadDispatcher.Id)),
      oidsWithAliases, true)
    //val f: Unit = trigger.f(henkilo, oidsWithAliases)
    //Thread.sleep(100000)
    println("great success")
    */
  }

  it should "parse 7 course LUVA data" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "LUVA.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    val result: Seq[SuoritusArvosanat] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo)
    result should have length 1
    val suoritus: SuoritusArvosanat = result.head
    suoritus.suoritus shouldBe a [VirallinenSuoritus]
    val virallinen = suoritus.suoritus.asInstanceOf[VirallinenSuoritus]
    virallinen.tila should equal("KESKEN")
  }

  it should "parse 25 course LUVA data" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "LUVA_25_kurssia.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    val result: Seq[SuoritusArvosanat] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo)
    result should have length 1
    val suoritus: SuoritusArvosanat = result.head
    suoritus.suoritus shouldBe a [VirallinenSuoritus]
    val virallinen = suoritus.suoritus.asInstanceOf[VirallinenSuoritus]
    virallinen.tila should equal("VALMIS")
  }

  it should "parse VALMA data" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "VALMA.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null

    val result: Seq[SuoritusArvosanat] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo)
    result should have length 1

    val suoritus = result.head
    suoritus.suoritus shouldBe a [VirallinenSuoritus]
    val virallinen = suoritus.suoritus.asInstanceOf[VirallinenSuoritus]

    virallinen.tila should equal("VALMIS")
  }

  it should "parse VALMA_kesken data" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "VALMA_kesken.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null

    val result: Seq[SuoritusArvosanat] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo)
    result should have length 1

    val suoritus = result.head
    suoritus.suoritus shouldBe a [VirallinenSuoritus]
    val virallinen = suoritus.suoritus.asInstanceOf[VirallinenSuoritus]

    virallinen.tila should equal("KESKEN")
  }

  class TestSureActor extends Actor {
    import akka.pattern.pipe

    override def receive: Receive = {
      case SuoritusQueryWithPersonAliases(q, personOidsWithAliases) =>
        //(henkilo: String, kuvaus: String, myontaja: String, vuosi: Int, tyyppi: String, index: Int = 0, lahde: String) extends Suoritus (henkilo, false, lahde) {
        val existing: VirallinenSuoritus = VirallinenSuoritus(komo = "komo", myontaja = "myontaja", tila = "KESKEN", valmistuminen = new LocalDate(),
          henkilo = "1.2.246.562.24.71123947024", yksilollistaminen = yksilollistaminen.Ei, suoritusKieli = "FI", lahde = "1.2.246.562.10.1234", vahv = false)
        //("1.2.246.562.24.71123947024", true, "koski") //val henkiloOid: String, val vahvistettu: Boolean, val source: String
        Future.successful(Seq(existing)) pipeTo sender
    }
  }
}
