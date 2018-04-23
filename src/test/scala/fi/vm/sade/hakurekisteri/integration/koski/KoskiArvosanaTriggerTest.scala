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
    val result: Seq[SuoritusArvosanat] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo).head
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
    val result: Seq[SuoritusArvosanat] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo).head
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
    val result: Seq[SuoritusArvosanat] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo).head
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

    val result = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo).head
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

    val result: Seq[SuoritusArvosanat] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo).head
    result should have length 1

    val suoritus = result.head
    suoritus.suoritus shouldBe a [VirallinenSuoritus]
    val virallinen = suoritus.suoritus.asInstanceOf[VirallinenSuoritus]

    virallinen.tila should equal("KESKEN")
  }

  it should "parse peruskoulu_lisäopetus_ei_vahvistettu.json data" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "peruskoulu_lisäopetus_ei_vahvistettu.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null

    val result: Seq[SuoritusArvosanat] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo).head
    result should have length 1

    val suoritus = result.head
    suoritus.suoritus shouldBe a [VirallinenSuoritus]
    val virallinen = suoritus.suoritus.asInstanceOf[VirallinenSuoritus]

    virallinen.tila should equal("KESKEYTYNYT")
  }

  it should "parse peruskoulu_lisäopetus.json data" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "peruskoulu_lisäopetus.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null

    val result: Seq[SuoritusArvosanat] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo).head
    result should have length 1

    val suoritus = result.head
    suoritus.suoritus shouldBe a [VirallinenSuoritus]
    val virallinen = suoritus.suoritus.asInstanceOf[VirallinenSuoritus]

    virallinen.tila should equal("VALMIS")
  }

  it should "parse peruskoulu_lisäopetus.json arvosanat" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "peruskoulu_lisäopetus.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null

    val result: Seq[SuoritusArvosanat] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo).head
    result should have length 1

    val suoritus = result.head
    suoritus.suoritus shouldBe a [VirallinenSuoritus]
    val virallinen = suoritus.suoritus.asInstanceOf[VirallinenSuoritus]

    virallinen.tila should equal("VALMIS")

    suoritus.arvosanat should have length 13
  }


  it should "parse peruskoulu_9_luokka_päättötodistus_jää_luokalle.json data" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "peruskoulu_9_luokka_päättötodistus_jää_luokalle.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.suoritukset(2).jääLuokalle shouldEqual Some(true)

    val result: Seq[SuoritusArvosanat] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo).head
    result should have length 4

    val suoritus = result(2)
    suoritus.suoritus shouldBe a [VirallinenSuoritus]
    val virallinen = suoritus.suoritus.asInstanceOf[VirallinenSuoritus]

    virallinen.tila should equal("KESKEYTYNYT")
    suoritus.arvosanat should have length 0

    val paattotodistus = result(3)

    val virallinenpaattotodistus = paattotodistus.suoritus.asInstanceOf[VirallinenSuoritus]
    virallinenpaattotodistus.komo shouldNot be("luokka")
    paattotodistus.arvosanat should have length 0
  }

  it should "parse peruskoulu_9_luokka_päättötodistus_vuosiluokkiinSitoutumatonOpetus_true.json data" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "peruskoulu_9_luokka_päättötodistus_vuosiluokkiinSitoutumatonOpetus_true.json").mkString
    val henkiloList: List[KoskiHenkiloContainer] = parse(json).extract[List[KoskiHenkiloContainer]]
    val henkilo = henkiloList.head
    henkilo should not be null
    val lisätiedot = henkilo.opiskeluoikeudet.head.lisätiedot
    lisätiedot shouldBe defined
    lisätiedot.get.vuosiluokkiinSitoutumatonOpetus should be(Some(true))

    val result: Seq[SuoritusArvosanat] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo).head
    result should have length 4

    val suoritus = result(2)
    suoritus.suoritus shouldBe a [VirallinenSuoritus]
    val virallinen = suoritus.suoritus.asInstanceOf[VirallinenSuoritus]

    virallinen.tila should equal("KESKEYTYNYT")


    val henkilo2 = henkiloList(1)
    henkilo2 should not be null
    val lisätiedot2 = henkilo.opiskeluoikeudet.head.lisätiedot
    lisätiedot2 shouldBe defined
    lisätiedot2.get.vuosiluokkiinSitoutumatonOpetus should be(Some(true))

    val result2: Seq[SuoritusArvosanat] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo).head
    result2 should have length 4

    val suoritus2 = result2(2)
    suoritus2.suoritus shouldBe a [VirallinenSuoritus]
    val virallinen2 = suoritus.suoritus.asInstanceOf[VirallinenSuoritus]

    virallinen2.tila should equal("KESKEYTYNYT")


  }

  it should "parse arvosanat from peruskoulu_9_luokka_päättötodistus.json" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "peruskoulu_9_luokka_päättötodistus.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    val result: Seq[SuoritusArvosanat] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo).head
    result should have length 4
  }

  it should "not parse arvosanat from peruskoulu_9_luokka_päättötodistus_vahvistus_4_6_2018_jälkeen.json" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "peruskoulu_9_luokka_päättötodistus_vahvistus_4_6_2018_jälkeen.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    val result: Seq[SuoritusArvosanat] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo).head
    result should have length 4
    result(3).arvosanat should have length 0
  }

  it should "not parse arvosanat from lukio_päättötodistus.json" in {
    /*
    Lukion päättötodistuksen (abiturienttien) arvosanat: Suoritusta ei pidä luoda, sillä hakijalla on jo
    hakemuksen perusteella luotu suoritus suoritusrekisterissä. Haetaan arvosanat hakijoille
    joiden lukion oppimäärän suoritus on vahvistettu KOSKI -palvelussa.
    Tässä vaiheessa ei haeta vielä lukion päättötodistukseen tehtyjä korotuksia.
     */

    val json: String = scala.io.Source.fromFile(jsonDir + "lukio_päättötodistus.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    val result: Seq[SuoritusArvosanat] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo).head
    result should have length 0
  }

  it should "parse BUG-1711.json" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "BUG-1711.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    val resultGroup: Seq[Seq[SuoritusArvosanat]] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo)
    resultGroup.head should have length 2 //kaksi opiskeluoikeutta joissa molemmissa yksi luokkatieto -> neljä suoritusarvosanaa
    resultGroup(1) should have length 2 //kaksi opiskeluoikeutta joissa molemmissa yksi luokkatieto -> neljä suoritusarvosanaa

    resultGroup(1)(0).luokka shouldEqual "SHKK"

    val system = ActorSystem("MySpec")
    val a = system.actorOf(Props(new TestSureActor()).withDispatcher(CallingThreadDispatcher.Id))
    val oidsWithAliases = PersonOidsWithAliases(Set("1.2.246.562.24.10101010101"), Map.empty)
    KoskiArvosanaTrigger.muodostaKoskiSuorituksetJaArvosanat(henkilo, a,
      system.actorOf(TestActors.blackholeProps.withDispatcher(CallingThreadDispatcher.Id)),
      system.actorOf(TestActors.blackholeProps.withDispatcher(CallingThreadDispatcher.Id)),
      oidsWithAliases, true)
    //val f: Unit = trigger.f(henkilo, oidsWithAliases)
    //Thread.sleep(100000) TODO FIX THREADING
    println("great success")
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
