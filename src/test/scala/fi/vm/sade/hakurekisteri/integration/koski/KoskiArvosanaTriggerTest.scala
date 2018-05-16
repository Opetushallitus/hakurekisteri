package fi.vm.sade.hakurekisteri.integration.koski

import akka.actor.{Actor, ActorSystem, Props}
import akka.testkit.{CallingThreadDispatcher, TestActors}
import fi.vm.sade.hakurekisteri.Oids
import fi.vm.sade.hakurekisteri.arvosana.Arvosana
import fi.vm.sade.hakurekisteri.integration.henkilo.PersonOidsWithAliases
import fi.vm.sade.hakurekisteri.integration.koski.KoskiArvosanaTrigger.SuoritusArvosanat
import fi.vm.sade.hakurekisteri.suoritus._
import org.joda.time.LocalDate
import org.joda.time.format.{DateTimeFormat, DateTimeFormatter}
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.scalatest.concurrent.AsyncAssertions
import org.scalatest.mock.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class KoskiArvosanaTriggerTest extends FlatSpec with Matchers with MockitoSugar with AsyncAssertions {

  implicit val formats = org.json4s.DefaultFormats

  private val jsonDir = "src/test/scala/fi/vm/sade/hakurekisteri/integration/koski/json/"

  it should "parse a koski henkilo" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "testikiira.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    val result: Seq[SuoritusArvosanat] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo).head
    val suoritusA = result.head
    val suoritusB = result(1)

    val expectedDate = new LocalDate(2017,8,1)
    suoritusB.lasnadate should equal (expectedDate)
    val oidsWithAliases = PersonOidsWithAliases(Set("1.2.246.562.24.71123947024"), Map.empty)
/*
    //TODO fix actor threading problem
    val system = ActorSystem("MySpec")
    val a = system.actorOf(Props(new TestSureActor()).withDispatcher(CallingThreadDispatcher.Id))

    KoskiArvosanaTrigger.muodostaKoskiSuorituksetJaArvosanat(henkilo, a,
      system.actorOf(TestActors.blackholeProps.withDispatcher(CallingThreadDispatcher.Id)),
      system.actorOf(TestActors.blackholeProps.withDispatcher(CallingThreadDispatcher.Id)),
      oidsWithAliases, true)
    //val trigger: KoskiTrigger = KoskiArvosanaTrigger(a, system.actorOf(TestActors.echoActorProps), system.actorOf(TestActors.echoActorProps))(system.dispatcher)
    //val trigger = KoskiTrigger(henkilo, oidsWithAliases)
    //val f: Unit = trigger.f(henkilo, oidsWithAliases)
    //Thread.sleep(100000)
    //
    */
    println("great success")

  }

  it should "parse 7 course LUVA data" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "LUVA.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi shouldEqual Some(KoskiKoodi("luva", "opiskeluoikeudentyyppi"))
    val result: Seq[SuoritusArvosanat] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo).head
    result should have length 1
    val suoritus: SuoritusArvosanat = result.head
    suoritus.suoritus shouldBe a [VirallinenSuoritus]
    val virallinen = suoritus.suoritus.asInstanceOf[VirallinenSuoritus]
    virallinen.tila should equal("KESKEN")

    virallinen.core.tyyppi should be("perusopetuksen oppiaineen suoritus")
  }
  it should "parse 23 course LUVA data" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "LUVA_23_kurssia.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    val numcourses: Int = KoskiArvosanaTrigger.getNumberOfAcceptedLuvaCourses(henkilo.opiskeluoikeudet.head.suoritukset.head.osasuoritukset)
    numcourses shouldBe 23
    val result: Seq[SuoritusArvosanat] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo).head
    result should have length 1
    val suoritus: SuoritusArvosanat = result.head
    suoritus.suoritus shouldBe a [VirallinenSuoritus]
    val virallinen = suoritus.suoritus.asInstanceOf[VirallinenSuoritus]
    virallinen.tila should equal("KESKEN")

    virallinen.core.tyyppi should be("perusopetuksen oppiaineen suoritus")
  }
  it should "parse 25 course LUVA data" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "LUVA_25_kurssia.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    val numcourses: Int = KoskiArvosanaTrigger.getNumberOfAcceptedLuvaCourses(henkilo.opiskeluoikeudet.head.suoritukset.head.osasuoritukset)
    numcourses shouldBe 25
    val result: Seq[SuoritusArvosanat] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo).head
    result should have length 1
    val suoritus: SuoritusArvosanat = result.head
    suoritus.suoritus shouldBe a [VirallinenSuoritus]
    val virallinen = suoritus.suoritus.asInstanceOf[VirallinenSuoritus]
    virallinen.tila should equal("VALMIS")

    virallinen.core.tyyppi should be("perusopetuksen oppiaineen suoritus")
  }

  it should "parse VALMA data" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "VALMA.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi shouldEqual Some(KoskiKoodi("ammatillinenkoulutus", "opiskeluoikeudentyyppi"))
    val result: Seq[SuoritusArvosanat] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo).head
    result should have length 1

    val suoritus = result.head
    suoritus.suoritus shouldBe a [VirallinenSuoritus]
    val virallinen = suoritus.suoritus.asInstanceOf[VirallinenSuoritus]

    result.head.arvosanat should have length 1

    virallinen.tila should equal("VALMIS")

    virallinen.core.tyyppi should be("perusopetuksen oppiaineen suoritus")

  }

  it should "parse VALMA_kesken data" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "VALMA_kesken.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    val result: Seq[SuoritusArvosanat] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo).head
    result should have length 1

    val suoritus = result.head
    suoritus.suoritus shouldBe a [VirallinenSuoritus]
    val virallinen = suoritus.suoritus.asInstanceOf[VirallinenSuoritus]

    virallinen.tila should equal("KESKEN")
    virallinen.core.tyyppi should be("perusopetuksen oppiaineen suoritus")
  }

  it should "parse peruskoulu_lisäopetus_ei_vahvistettu.json data" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "peruskoulu_lisäopetus_ei_vahvistettu.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
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
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
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
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
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
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
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
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
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
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    val result: Seq[SuoritusArvosanat] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo).head
    result should have length 4
  }

  it should "not parse arvosanat from peruskoulu_9_luokka_päättötodistus_vahvistus_4_6_2018_jälkeen.json" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "peruskoulu_9_luokka_päättötodistus_vahvistus_4_6_2018_jälkeen.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
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
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    val result: Seq[SuoritusArvosanat] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo).head
    result should have length 0
  }

  it should "parse BUG-1711.json" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "BUG-1711.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    val resultGroup: Seq[Seq[SuoritusArvosanat]] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo)
    resultGroup.head should have length 2 //kaksi opiskeluoikeutta joissa molemmissa yksi luokkatieto -> neljä suoritusarvosanaa
    resultGroup(1) should have length 2 //kaksi opiskeluoikeutta joissa molemmissa yksi luokkatieto -> neljä suoritusarvosanaa

    resultGroup(1)(0).luokka shouldEqual "SHKK"

    val system = ActorSystem("MySpec")
    val a = system.actorOf(Props(new TestSureActor()).withDispatcher(CallingThreadDispatcher.Id))
    val oidsWithAliases = PersonOidsWithAliases(Set("1.2.246.562.24.10101010101"), Map.empty)
    /*
    KoskiArvosanaTrigger.muodostaKoskiSuorituksetJaArvosanat(henkilo, a,
      system.actorOf(TestActors.blackholeProps.withDispatcher(CallingThreadDispatcher.Id)),
      system.actorOf(TestActors.blackholeProps.withDispatcher(CallingThreadDispatcher.Id)),
      oidsWithAliases, true)
    //val f: Unit = trigger.f(henkilo, oidsWithAliases)
    //Thread.sleep(100000) TODO FIX THREADING*/
    println("great success")
  }

  it should "parse ammu_heluna.json" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "ammu_heluna.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    val resultGroup: Seq[Seq[SuoritusArvosanat]] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo)
    resultGroup should have length 1

    val s = resultGroup.head
    s should have length 3
    val kokonaisuus = s.head
    val kotitaloudet = kokonaisuus.arvosanat.filter(_.aine.contentEquals("KO"))



    val b2kielet = kokonaisuus.arvosanat.filter(_.aine.contentEquals("B2"))
    b2kielet should have length 1
    b2kielet.filter(_.valinnainen == true) should have length 1


    val a1kielet: Seq[Arvosana] = kokonaisuus.arvosanat.filter(_.aine.contentEquals("A1"))
    a1kielet should have length 2
    a1kielet.filter(_.valinnainen == false) should have length 1
    a1kielet.filter(_.valinnainen == true) should have length 1

/*
    val b1kielet = kokonaisuus.arvosanat.filter(_.aine.contentEquals("B1"))
    b1kielet should have length 2
    b1kielet.filter(_.valinnainen == false) should have length 1
    b1kielet.filter(_.valinnainen == true) should have length 1
*/

    kotitaloudet.filter(_.valinnainen == false) should have length 1
    kotitaloudet.filter(_.valinnainen) should have length 1
  }

  it should "not accept data without 9nth grade finished in 8_luokka.json" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "8_luokka.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    henkilo should not be null
    val resultGroup: Seq[Seq[SuoritusArvosanat]] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo)
    resultGroup should have length 1
    resultGroup.head should have length 0
  }

  it should "test data jäänyt_luokalle_peruskoulu.json" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "jäänyt_luokalle_peruskoulu.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    val resultGroup: Seq[Seq[SuoritusArvosanat]] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo)
    resultGroup should have length 1
    resultGroup.head should have length 3

    //TODO fix actor threading problem
    val system = ActorSystem("MySpec")
    val a = system.actorOf(Props(new TestSureActor()).withDispatcher(CallingThreadDispatcher.Id))

    val oidsWithAliases = PersonOidsWithAliases(Set("1.2.246.562.24.35601800632"), Map.empty)

    KoskiArvosanaTrigger.muodostaKoskiSuorituksetJaArvosanat(henkilo, a,
      system.actorOf(TestActors.blackholeProps.withDispatcher(CallingThreadDispatcher.Id)),
      system.actorOf(TestActors.blackholeProps.withDispatcher(CallingThreadDispatcher.Id)),
      oidsWithAliases, true)
    //val trigger: KoskiTrigger = KoskiArvosanaTrigger(a, system.actorOf(TestActors.echoActorProps), system.actorOf(TestActors.echoActorProps))(system.dispatcher)
    //val trigger = KoskiTrigger(henkilo, oidsWithAliases)
    //val f: Unit = trigger.f(henkilo, oidsWithAliases)
    //Thread.sleep(100000)
    //
    //println("great success")
  }

  it should "parse 1.2.246.562.24.40546864498.json" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "1.2.246.562.24.40546864498.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    val resultGroup: Seq[Seq[SuoritusArvosanat]] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo)
    resultGroup should have length 1
    resultGroup.head should have length 2

    val arvosanat: Seq[SuoritusArvosanat] = resultGroup.head
    arvosanat should have length 2
    val numKo = henkilo.opiskeluoikeudet.head.suoritukset.head.osasuoritukset.count(_.koulutusmoduuli.tunniste.get.koodiarvo.contentEquals("KO"))
    arvosanat.head.arvosanat.filter(_.aine.contentEquals("KO")) should have length numKo - 1 //one KO has only 1 vuosiviikkotunti, it's not accepted
  }

  it should "parse VALMA_korotettava_historia.json" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "VALMA_korotettava_historia.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    val resultGroup: Seq[Seq[SuoritusArvosanat]] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo)
    resultGroup should have length 2
    resultGroup.last should have length 1
    val suoritusarvosanat: Seq[SuoritusArvosanat] = resultGroup.last
    suoritusarvosanat should have length 1
    val suoritusarvosana: SuoritusArvosanat = suoritusarvosanat.head
    suoritusarvosana.arvosanat.exists(_.aine == "HI1") shouldBe true

    val virallinensuoritus = suoritusarvosana.suoritus.asInstanceOf[VirallinenSuoritus]
    val luokkaAste = Some(9)
    val AIKUISTENPERUS_LUOKKAASTE = "AIK"

    val foo = virallinensuoritus.komo.equals(Oids.perusopetusKomoOid)
    val bar = suoritusarvosanat.exists(_.luokkataso.getOrElse("").startsWith("9")) || luokkaAste.getOrElse("").equals(AIKUISTENPERUS_LUOKKAASTE)
    val peruskoulututkintoJaYsisuoritusTaiPKAikuiskoulutus = foo && bar

    if (virallinensuoritus.komo.equals("luokka") || !(peruskoulututkintoJaYsisuoritusTaiPKAikuiskoulutus || !virallinensuoritus.komo.equals(Oids.perusopetusKomoOid))) {
      fail("should not be here")
    }
  }

  it should "parse testi_satu_valinnaiset.json" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "testi_satu_valinnaiset.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    val s = henkilo.opiskeluoikeudet.head.suoritukset.flatMap(_.osasuoritukset)

    val sourcematikat = henkilo.opiskeluoikeudet.flatMap(p => p.suoritukset)
      .flatMap(_.osasuoritukset)
      .filter(f => f.koulutusmoduuli.tunniste.get.koodiarvo == "MA")

    val sourcekässät = henkilo.opiskeluoikeudet.flatMap(p => p.suoritukset)
      .flatMap(_.osasuoritukset)
      .filter(f => f.koulutusmoduuli.tunniste.get.koodiarvo == "KS")

    sourcematikat.exists(_.koulutusmoduuli.pakollinen.contains(true))
    sourcematikat.exists(_.koulutusmoduuli.pakollinen.contains(false))

    sourcekässät.exists(_.koulutusmoduuli.pakollinen.contains(true))
    sourcekässät.exists(_.koulutusmoduuli.pakollinen.contains(false))

    val resultGroup: Seq[Seq[SuoritusArvosanat]] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo)
    resultGroup should have length 1
    val head = resultGroup.head.head
    val matikat = head.arvosanat.filter(_.aine.contentEquals("MA"))
    val kässät = head.arvosanat.filter(_.aine.contentEquals("KS"))
    matikat should have length 2
    kässät should have length 2
    matikat.count(_.valinnainen == true) shouldBe 1
    matikat.count(_.valinnainen == false) shouldBe 1

    kässät.count(_.valinnainen == true) shouldBe 1
    kässät.count(_.valinnainen == false) shouldBe 1
  }

  it should "parse testi3_satu_valinnaisuus.json" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "testi3_satu_valinnaisuus.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    val s = henkilo.opiskeluoikeudet.head.suoritukset.flatMap(_.osasuoritukset)

    val sourceliikunnat = henkilo.opiskeluoikeudet.flatMap(p => p.suoritukset)
      .flatMap(_.osasuoritukset)
      .filter(f => f.koulutusmoduuli.tunniste.get.koodiarvo == "LI")

    sourceliikunnat should have length 2

    val res: Seq[Seq[SuoritusArvosanat]] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo)

    res should have length 1
    val suoritusarvosanat: SuoritusArvosanat = res.head.head
    val arvosanat = suoritusarvosanat.arvosanat.filter(_.aine == "LI")
    arvosanat should have length 2
    arvosanat.exists(_.valinnainen == true) shouldBe true
    arvosanat.exists(_.valinnainen == false) shouldBe true
  }

  it should "parse perusopetus_valinnaisuus_conflu_7.json" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "perusopetus_valinnaisuus_conflu_7.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    val res: Seq[Seq[SuoritusArvosanat]] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo)

    res should have length 1
    val suoritusarvosanat: SuoritusArvosanat = res.head.head
    val musiikkiarvosanat = suoritusarvosanat.arvosanat.filter(_.aine == "MU")
    musiikkiarvosanat should have length 2
    musiikkiarvosanat.exists(_.valinnainen == true) shouldBe true
    musiikkiarvosanat.exists(_.valinnainen == false) shouldBe true


    val fyssaarvosanat = suoritusarvosanat.arvosanat.filter(_.aine == "FY")
    fyssaarvosanat should have length 1
    fyssaarvosanat.exists(_.valinnainen == true) shouldBe false //too short courses are pruned
    fyssaarvosanat.exists(_.valinnainen == false) shouldBe true

    val kotitalous = suoritusarvosanat.arvosanat.filter(_.aine == "KO")
    kotitalous should have length 2
    kotitalous.exists(_.valinnainen == true) shouldBe true
    kotitalous.exists(_.valinnainen == false) shouldBe true

    val hissa = suoritusarvosanat.arvosanat.filter(_.aine == "HI")
    hissa should have length 2
    hissa.exists(_.valinnainen == true) shouldBe true
    hissa.exists(_.valinnainen == false) shouldBe true
  }

  it should "parse luokallejaanyt_conflu_10.json" in {
    /*
    Valmistumispäivämäärä ei päivittynyt todistuksen vahvistuspäivämääräkentästä kosken puolelta sureen,
    ennen kuin opiskeluoideuden tilan oli muuttanut valmiiksi koskessa. Olisi pitänyt päivittyä jo silloin,
    kun päättötodistus vahvistettiin. (Vaikka opiskeluoikeuden tila oli kesken)
    Testattu tapauksella 080501A660R
     */

    val json: String = scala.io.Source.fromFile(jsonDir + "luokallejaanyt_conflu_10.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    val res: Seq[Seq[SuoritusArvosanat]] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo)
    res should have length 1
    val arvosanat: Seq[SuoritusArvosanat] = res.head
    arvosanat should have length 3

    val ptodistus = arvosanat.head
    val suoritus = ptodistus.suoritus
    suoritus shouldBe a [VirallinenSuoritus]
    val virallinen = suoritus.asInstanceOf[VirallinenSuoritus]

    val expectedDate = LocalDate.parse("2018-05-07", DateTimeFormat.forPattern("yyyy-MM-dd"))

    virallinen.valmistuminen shouldBe expectedDate

  }

  class TestSureActor extends Actor {
    import akka.pattern.pipe

    override def receive: Receive = {
      case SuoritusQueryWithPersonAliases(q, personOidsWithAliases) =>
        //(henkilo: String, kuvaus: String, myontaja: String, vuosi: Int, tyyppi: String, index: Int = 0, lahde: String) extends Suoritus (henkilo, false, lahde) {
        val existing: VirallinenSuoritus = VirallinenSuoritus(komo = "komo", myontaja = "myontaja", tila = "KESKEN", valmistuminen = new LocalDate(),
          henkilo = "1.2.246.562.24.71123947024", yksilollistaminen = yksilollistaminen.Ei, suoritusKieli = "FI", lahde = "1.2.246.562.10.1234", vahv = false)
        //("1.2.246.562.24.71123947024", true, "koski") //val henkiloOid: String, val vahvistettu: Boolean, val source: String
        Future.failed(new RuntimeException("test")) pipeTo sender
        //Future.successful(Seq()) pipeTo sender
    }
  }
}
