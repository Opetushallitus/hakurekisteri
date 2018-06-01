package fi.vm.sade.hakurekisteri.integration.koski

import akka.actor.{Actor, ActorSystem, Props}
import akka.testkit.{CallingThreadDispatcher, TestActors}
import fi.vm.sade.hakurekisteri.Oids
import fi.vm.sade.hakurekisteri.arvosana._
import fi.vm.sade.hakurekisteri.integration.henkilo.PersonOidsWithAliases
import fi.vm.sade.hakurekisteri.integration.koski.KoskiArvosanaTrigger.{AIKUISTENPERUS_LUOKKAASTE, SuoritusArvosanat}
import fi.vm.sade.hakurekisteri.suoritus._
import org.joda.time.{LocalDate, LocalDateTime}
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

    val ysit = getYsiluokat(result)
    val suoritusA = result.head
    val suoritusB = result(1)

    val expectedDate = new LocalDate(2017,8,1)
    suoritusB.lasnadate should equal (expectedDate)

    getPerusopetusPäättötodistus(result).get.luokka shouldEqual "9A"
    getYsiluokat(result).head.luokka shouldEqual "9A"

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

  it should "parse vahvistettu 23 course LUVA data" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "LUVA_23_kurssia_vahvistettu.json").mkString
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
    virallinen.tila should equal("VALMIS")

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

    virallinen.tila should equal("KESKEYTYNYT")
    virallinen.core.tyyppi should be("perusopetuksen oppiaineen suoritus")
  }
/*
  //TODO is the test data valid???
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
*/
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
    getPerusopetusPäättötodistus(result).get.luokka shouldEqual "9C"

    val virallinenpaattotodistus = paattotodistus.suoritus.asInstanceOf[VirallinenSuoritus]
    virallinenpaattotodistus.komo shouldNot be("luokka")
    paattotodistus.arvosanat should have length 0

    peruskouluB2KieletShouldNotBeValinnainen(result)
  }

  it should "parse peruskoulu_9_luokka_päättötodistus_ei_vahvistusta.json data" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "peruskoulu_9_luokka_päättötodistus_ei_vahvistusta.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    val result: Seq[SuoritusArvosanat] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo).head
    result should have length 4
    val pt = getPerusopetusPäättötodistus(result).get
    pt.arvosanat should have length 0
  }

  it should "parse peruskoulu_9_luokka_päättötodistus_ei_vahvistusta_yksi_nelonen.json data" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "peruskoulu_9_luokka_päättötodistus_ei_vahvistusta_yksi_nelonen.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo.opiskeluoikeudet.filter(_.tyyppi.get.koodiarvo.contentEquals("perusopetuksenoppimaara"))
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    val result: Seq[SuoritusArvosanat] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo).head
    result should have length 4
    val pt = getPerusopetusPäättötodistus(result).get
    pt.arvosanat should have length 18
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


    peruskouluB2KieletShouldNotBeValinnainen(result)

    result should have length 4
    getPerusopetusPäättötodistus(result).get.luokka shouldEqual "9C"
    val suoritus = result(2)
    suoritus.suoritus shouldBe a [VirallinenSuoritus]
    val virallinen = suoritus.suoritus.asInstanceOf[VirallinenSuoritus]

    virallinen.tila should equal("VALMIS")


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

    virallinen2.tila should equal("VALMIS")
  }

  it should "parse arvosanat from peruskoulu_9_luokka_päättötodistus.json" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "peruskoulu_9_luokka_päättötodistus.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    val result: Seq[SuoritusArvosanat] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo).head
    result should have length 4
    val pt = getPerusopetusPäättötodistus(result).get
    pt.luokka shouldEqual "9C"
    pt.suoritus.asInstanceOf[VirallinenSuoritus].valmistuminen shouldEqual LocalDate.parse("2016-06-04")

    peruskouluB2KieletShouldNotBeValinnainen(result)
    val ysi = getYsiluokat(result).head
    val virallinenysi = ysi.suoritus.asInstanceOf[VirallinenSuoritus]

  }

  it should "not parse arvosanat from peruskoulu_9_luokka_päättötodistus_vahvistus_4_6_2018_jälkeen.json" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "peruskoulu_9_luokka_päättötodistus_vahvistus_4_6_2018_jälkeen.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    val result: Seq[SuoritusArvosanat] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo).head
    result should have length 4
    getPerusopetusPäättötodistus(result).get.luokka shouldEqual "9C"
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

  it should "parse arvosanat from lukio_päättötodistus.json when switch to enable lukio import is enabled" in {
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
    val result: Seq[SuoritusArvosanat] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo, createLukioArvosanat = true).head
    result should have length 1

    val suoritusArvosanat = result.head
    val virallinensuoritus = suoritusArvosanat.suoritus.asInstanceOf[VirallinenSuoritus]
    val arvosanat = suoritusArvosanat.arvosanat
/*
    val expectedAineet: Set[String] = Set("AI", "A1", "B1", "B3", "MA", "BI", "GE", "FY", "KE", "KT", "FI", "PS", "HI", "YH", "LI", "MU", "KU", "TE", "ITT", "TO", "OA")
*/
    val expectedAineet: Set[String] = Set("AI", "A1", "B1", "B3", "MA", "BI", "GE", "FY", "KE", "KT", "FI", "PS", "HI", "YH", "LI", "MU", "KU", "TE")
    val aineet: Set[String] = arvosanat.map(a => a.aine).toSet

    aineet.toSeq.sorted shouldEqual expectedAineet.toSeq.sorted

    virallinensuoritus.tila shouldEqual "VALMIS"
    arvosanat.forall(_.valinnainen == false) shouldEqual true

  }

  it should "parse arvosanat from lukio_päättötodistus2.json when switch to enable lukio import is enabled" in {

    val json: String = scala.io.Source.fromFile(jsonDir + "lukio_päättötodistus2.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    val result: Seq[SuoritusArvosanat] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo, createLukioArvosanat = true).head
    result should have length 1

    val suoritusArvosanat = result.head
    val virallinensuoritus = suoritusArvosanat.suoritus.asInstanceOf[VirallinenSuoritus]
    val arvosanat = suoritusArvosanat.arvosanat


    val expectedAineet: Set[String] = Set("AI", "A1", "B1", "MA", "FY", "KE", "BI", "GE", "KT", "FI", "PS", "HI", "YH", "MU", "KU", "TE", "LI")
    val aineet: Set[String] = arvosanat.map(a => a.aine).toSet

    aineet.toSeq.sorted shouldEqual expectedAineet.toSeq.sorted
    virallinensuoritus.tila shouldEqual "VALMIS"


    val arvosanatuple = arvosanat.map(a => (a.aine, a.valinnainen)).toSet
    val expectedAineetTuple: Set[(String, Boolean)] = Set("AI", "A1", "B1", "MA", "FY", "KE", "BI", "GE", "PS", "KT", "FI", "HI", "YH", "MU", "KU", "TE", "LI").map(s => (s, false))
    arvosanatuple shouldEqual expectedAineetTuple

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
    getPerusopetusPäättötodistus(s).get.luokka shouldEqual "9C"
    s should have length 3
    val kokonaisuus = s.head
    val kotitaloudet = kokonaisuus.arvosanat.filter(_.aine.contentEquals("KO"))

    val b2kielet = kokonaisuus.arvosanat.filter(_.aine.contentEquals("B2"))
    b2kielet should have length 1
    b2kielet.filter(_.valinnainen == true) should have length 0

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
    getPerusopetusPäättötodistus(resultGroup.head).get.luokka shouldEqual "9C"
    //TODO fix actor threading problem
    /*val system = ActorSystem("MySpec")
    val a = system.actorOf(Props(new TestSureActor()).withDispatcher(CallingThreadDispatcher.Id))

    val oidsWithAliases = PersonOidsWithAliases(Set("1.2.246.562.24.35601800632"), Map.empty)

    KoskiArvosanaTrigger.muodostaKoskiSuorituksetJaArvosanat(henkilo, a,
      system.actorOf(TestActors.blackholeProps.withDispatcher(CallingThreadDispatcher.Id)),
      system.actorOf(TestActors.blackholeProps.withDispatcher(CallingThreadDispatcher.Id)),
      oidsWithAliases, true)*/
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
    getPerusopetusPäättötodistus(arvosanat).get.luokka shouldEqual "9H"
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
    suoritusarvosana.arvosanat.exists(_.aine == "HI") shouldBe true
    val virallinensuoritus = suoritusarvosana.suoritus.asInstanceOf[VirallinenSuoritus]
    virallinensuoritus.komo shouldEqual Oids.perusopetuksenOppiaineenOppimaaraOid

    val luokkaAste = Some(9)
    val AIKUISTENPERUS_LUOKKAASTE = "AIK"

    val foo = virallinensuoritus.komo.equals(Oids.perusopetusKomoOid)
    val bar = suoritusarvosanat.exists(_.luokkataso.getOrElse("").startsWith("9")) || luokkaAste.getOrElse("").equals(AIKUISTENPERUS_LUOKKAASTE)
    val peruskoulututkintoJaYsisuoritusTaiPKAikuiskoulutus = foo && bar

    if (virallinensuoritus.komo.equals("luokka") || !(peruskoulututkintoJaYsisuoritusTaiPKAikuiskoulutus || !virallinensuoritus.komo.equals(Oids.perusopetusKomoOid))) {
      fail("should not be here")
    }
  }

  it should "combine multiple perusopetuksen oppiaineen oppimäärä data that share the same organisation" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "perusopetuksenOppimaaraCombine.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty


    //aine appended by grade
    val myöntäjäOrgAineet = List("AI7", "AI5", "MA5", "KT5", "HI7", "YH6", "FY5", "KE5", "BI6", "GE5", "TEH")
    val myöntäjäOrg2Aineet = List("TEH")

    val resultGroup = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo)
    resultGroup should have length 1
    val result = resultGroup.head
    val resultByOrg = result.groupBy(_.suoritus.asInstanceOf[VirallinenSuoritus].myontaja)

    resultByOrg.keys.toSeq.sorted shouldEqual Seq("myöntäjäOrg", "myöntäjäOrg2")

    resultByOrg("myöntäjäOrg").filter(_.suoritus.asInstanceOf[VirallinenSuoritus].komo.contentEquals(Oids.perusopetuksenOppiaineenOppimaaraOid)) should have length 1
    resultByOrg("myöntäjäOrg2").filter(_.suoritus.asInstanceOf[VirallinenSuoritus].komo.contentEquals(Oids.perusopetuksenOppiaineenOppimaaraOid)) should have length 1

    resultByOrg("myöntäjäOrg").head.arvosanat.map(a => a.aine.concat(a.arvio match {
      case Arvio410(arvosana) => arvosana
      case ArvioYo(arvosana, pisteet) => arvosana
      case ArvioOsakoe(arvosana) => arvosana
      case ArvioHyvaksytty(arvosana) => if (arvosana.contentEquals("hylatty")) "H" else arvosana
    })).sorted shouldEqual myöntäjäOrgAineet.sorted


    resultByOrg("myöntäjäOrg2").head.arvosanat.map(a => a.aine.concat(a.arvio match {
      case Arvio410(arvosana) => arvosana
      case ArvioYo(arvosana, pisteet) => arvosana
      case ArvioOsakoe(arvosana) => arvosana
      case ArvioHyvaksytty(arvosana) => if (arvosana.contentEquals("hylatty")) "H" else arvosana
    })).sorted shouldEqual myöntäjäOrg2Aineet.sorted

    resultByOrg("myöntäjäOrg").head.suoritus.asInstanceOf[VirallinenSuoritus].valmistuminen shouldEqual LocalDate.parse("2018-02-19")
    resultByOrg("myöntäjäOrg").head.suoritus.asInstanceOf[VirallinenSuoritus].myontaja shouldEqual "myöntäjäOrg"
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
    var henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
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

  it should "parse kymppiluokkatesti_kesken_conflu_2.json" in {
    /*
    Hetu 140601A511L (kymppiluokkalainen). Lisäsin koskeen sekä viime vuonna suoritetun perusopetuksen,
    että tänä vuonna suoritettavan 10-luokan ja muutaman arvosanan korotuksen. → 10-luokka arvosanoineen siirtyi,
    mutta suressa suorituksen tilana näkyy 'Keskeytynyt', vaikka Koskessa on läsnä.
    Pitäisi olla suressakin keskeneräisenä.(Minna Turunen 8.5.)
    */
    val json: String = scala.io.Source.fromFile(jsonDir + "kymppiluokkatesti_kesken_conflu_2.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty


    val seq: Seq[Seq[SuoritusArvosanat]] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo)
    seq should have length 2

    val res = seq(1)
    res should have length 1
    val suor: SuoritusArvosanat = res.head

    suor.suoritus should not be null
    val virallinen = suor.suoritus.asInstanceOf[VirallinenSuoritus]
    virallinen.komo shouldEqual Oids.lisaopetusKomoOid
    virallinen.tila shouldEqual "KESKEN"

  }

  it should "parse luokkatietoinen-testi.json" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "luokkatietoinen-testi.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    val resgroup: Seq[Seq[SuoritusArvosanat]] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo)
    resgroup should have length 1

    val res: Seq[SuoritusArvosanat] = resgroup.head
    res should have length 3

    res.exists(_.luokka.contentEquals("9A")) shouldEqual true
    res.exists(_.luokka.contentEquals("9D")) shouldEqual true

    val luokkatieto = res.filter(_.luokka.contentEquals("9D")).head
    val virallinensuoritus = luokkatieto.suoritus.asInstanceOf[VirallinenSuoritus]
    virallinensuoritus.tila shouldEqual "KESKEN"

    val päättötodistus: SuoritusArvosanat = res.filter(_.suoritus.asInstanceOf[VirallinenSuoritus].komo.contentEquals(Oids.perusopetusKomoOid)).head

    val AIKUISTENPERUS_LUOKKAASTE = "AIK"
    res.foreach {
      case SuoritusArvosanat(useSuoritus: VirallinenSuoritus, arvosanat: Seq[Arvosana], luokka: String, lasnaDate: LocalDate, luokkaTaso: Option[String]) =>

        val peruskoulututkintoJaYsisuoritusTaiPKAikuiskoulutus = useSuoritus.komo.equals(Oids.perusopetusKomoOid) && (res.exists(_.luokkataso.getOrElse("").startsWith("9"))
          || luokkaTaso.getOrElse("").equals(AIKUISTENPERUS_LUOKKAASTE))
        if (!useSuoritus.komo.equals("luokka") && (peruskoulututkintoJaYsisuoritusTaiPKAikuiskoulutus || !useSuoritus.komo.equals(Oids.perusopetusKomoOid))) {
          peruskoulututkintoJaYsisuoritusTaiPKAikuiskoulutus shouldBe true
          useSuoritus.tila shouldEqual "KESKEN"
        } else {
          val ismatch = luokka.contentEquals("9A") || luokka.contentEquals("9D")
          ismatch shouldBe true
        }
    }

    //päättötodistus.luokka shouldBe empty
    //päättötodistus.suoritus.asInstanceOf[VirallinenSuoritus].tila shouldEqual "KESKEN"
    //päättötodistus.suoritus.vahvistettu shouldBe false

  }

  it should "parse luokallejääjä_testi.json" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "luokallejääjä_testi.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    val res = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo)
    res should have length 1
    val pt = getPerusopetusPäättötodistus(res.head).get
    val ysiluokat = getYsiluokat(res.head)
    ysiluokat should have length 1
    ysiluokat.head.suoritus.asInstanceOf[VirallinenSuoritus].valmistuminen.toString("dd.MM.YYYY") shouldEqual "01.06.2017"
    pt.suoritus.asInstanceOf[VirallinenSuoritus].valmistuminen.toString("dd.MM.YYYY") shouldEqual "01.06.2017"
  }

  it should "get correct end date from last ysiluokka" in {
    val koskikomo = KoskiKoulutusmoduuli(None, None, None, None, None)

    val vahvistus = KoskiVahvistus("2000-04-01", KoskiOrganisaatio(""))
    val vahvistus2 = KoskiVahvistus("2000-05-03", KoskiOrganisaatio(""))
    val vahvistus3 = KoskiVahvistus("2000-05-02", KoskiOrganisaatio(""))

    val ks1 = KoskiSuoritus(luokka = Some("9"),
                           koulutusmoduuli = koskikomo,
                            tyyppi = None,
                            kieli = None,
                            pakollinen = None,
                            toimipiste = None,
                            vahvistus = Some(vahvistus),
                            suorituskieli = None,
                            arviointi = None,
                            yksilöllistettyOppimäärä = None,
                            osasuoritukset = Seq(),
                            ryhmä = None,
                            alkamispäivä = None,
                            jääLuokalle = None)

    val ks2 = KoskiSuoritus(luokka = Some("9"),
      koulutusmoduuli = koskikomo,
      tyyppi = None,
      kieli = None,
      pakollinen = None,
      toimipiste = None,
      vahvistus = Some(vahvistus2),
      suorituskieli = None,
      arviointi = None,
      yksilöllistettyOppimäärä = None,
      osasuoritukset = Seq(),
      ryhmä = None,
      alkamispäivä = None,
      jääLuokalle = None)

    val ks3 = KoskiSuoritus(luokka = Some("9"),
      koulutusmoduuli = koskikomo,
      tyyppi = None,
      kieli = None,
      pakollinen = None,
      toimipiste = None,
      vahvistus = Some(vahvistus3),
      suorituskieli = None,
      arviointi = None,
      yksilöllistettyOppimäärä = None,
      osasuoritukset = Seq(),
      ryhmä = None,
      alkamispäivä = None,
      jääLuokalle = None)

    val suoritukset: Seq[KoskiSuoritus] = Seq(ks1,ks2,ks3)
    val maybedate: Option[LocalDate] = KoskiArvosanaTrigger.getEndDateFromLastNinthGrade(suoritukset)

    maybedate.get shouldEqual KoskiArvosanaTrigger.parseLocalDate("2000-05-03")

  }

  it should "parse 1.2.246.562.24.14978931242.json" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "1.2.246.562.24.14978931242.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]

    henkilo.opiskeluoikeudet.head.aikaleima shouldEqual Some("2018-05-15T11:59:16.690066")
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    val resultgroup = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo)
    resultgroup should have length 1
    val result: Seq[SuoritusArvosanat] = resultgroup.head
    result should have length 2
    val arvosanat = result.head

    val expectedDate = LocalDate.parse("2018-05-15")
    arvosanat.suoritus.asInstanceOf[VirallinenSuoritus].valmistuminen shouldEqual expectedDate
  }

  it should "filter valinnaiset aineet from aikuisten_perusopetus_valinnaiset.json" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "aikuisten_perusopetus_valinnaiset.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]

    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    val resultgroup = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo)
    resultgroup should have length 1
    val result: Seq[SuoritusArvosanat] = resultgroup.head
    result should have length 1

    val fysiikat = result.head.arvosanat.filter(_.aine.contentEquals("FY"))
    fysiikat should have length 1
    fysiikat.head.valinnainen shouldBe false

  }

  it should "parse telma_testi_valmis.json" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "telma_testi_valmis.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]

    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    val resultgrp = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo)
    resultgrp should have length 1
    val result = resultgrp.head
    result should have length 1

    val arvosanat = result.head.arvosanat
    val virallinensuoritus = result.head.suoritus.asInstanceOf[VirallinenSuoritus]

    arvosanat should have length 0
    virallinensuoritus.tila shouldEqual "VALMIS"
  }

  it should "parse telma_testi_kesken.json" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "telma_testi_kesken.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]

    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    val resultgrp = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo)
    resultgrp should have length 1
    val result = resultgrp.head
    result should have length 1

    val arvosanat = result.head.arvosanat
    val virallinensuoritus = result.head.suoritus.asInstanceOf[VirallinenSuoritus]

    arvosanat should have length 0
    virallinensuoritus.tila shouldEqual "KESKEYTYNYT"
  }

  it should "parse kielivalinnaisuustest.json" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "kielivalinnaisuustest.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]

    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    val resgroup = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo)
    val res = resgroup.head

    val arvosanat = getPerusopetusPäättötodistus(res).get.arvosanat
    peruskouluB2KieletShouldNotBeValinnainen(res)
  }

  it should "parse lukio_et_kt.json" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "lukio_et_kt.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]

    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    val resgroup = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo, createLukioArvosanat = true)
    val res: Seq[SuoritusArvosanat] = resgroup.head

    val arvosanat: Seq[Arvosana] = res.head.arvosanat
    arvosanat.count(_.aine.contentEquals("KT")) shouldEqual 2
  }

  it should "parse perusopetus_valinnaisuus_B1_yhteinen_ja_valinnainen.json" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "perusopetus_valinnaisuus_B1_yhteinen_ja_valinnainen.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]

    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    val resgroup: Seq[Seq[SuoritusArvosanat]] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo)
    val pt = getPerusopetusPäättötodistus(resgroup.head)
    pt.get.arvosanat.filter(_.aine.contentEquals("B1")) should have length 2
    pt.get.arvosanat.filter(a => a.aine.contentEquals("B1") && a.valinnainen) should have length 1
    pt.get.arvosanat.filter(a => a.aine.contentEquals("B1") && !a.valinnainen) should have length 1

  }

  def getPerusopetusPäättötodistus(arvosanat: Seq[SuoritusArvosanat]): Option[SuoritusArvosanat] = {
    arvosanat.find(_.suoritus.asInstanceOf[VirallinenSuoritus].komo.contentEquals(Oids.perusopetusKomoOid))
  }

  def getYsiluokat(arvosanat: Seq[SuoritusArvosanat]): Seq[SuoritusArvosanat] = {
    val luokat = arvosanat.filter(a => a.suoritus.asInstanceOf[VirallinenSuoritus].komo.contentEquals("luokka") && a.luokka.startsWith("9"))
    luokat
  }

  def getPerusopetusB2Kielet(arvosanat: Seq[SuoritusArvosanat]): Seq[Arvosana] = {
    val pk: Option[SuoritusArvosanat] = getPerusopetusPäättötodistus(arvosanat)
    pk match {
      case Some(t) => t.arvosanat.filter(_.aine.contentEquals("B2"))
      case None => return Seq.empty
    }
  }

  def allPeruskouluB2KieletShouldNotBeValinnainen(arvosanat: Seq[Seq[SuoritusArvosanat]]): Unit = {
    arvosanat.foreach(s => peruskouluB2KieletShouldNotBeValinnainen(s))
  }

  def peruskouluB2KieletShouldNotBeValinnainen(arvosanat: Seq[SuoritusArvosanat]): Unit = {
    getPerusopetusB2Kielet(arvosanat).foreach(_.valinnainen shouldEqual false)
  }

  class TestSureActor extends Actor {
    import akka.pattern.pipe

    override def receive: Receive = {
      case SuoritusQueryWithPersonAliases(q, personOidsWithAliases) =>
        //(henkilo: String, kuvaus: String, myontaja: String, vuosi: Int, tyyppi: String, index: Int = 0, lahde: String) extends Suoritus (henkilo, false, lahde) {
        val existing: VirallinenSuoritus = VirallinenSuoritus(komo = "komo", myontaja = "myontaja", tila = "KESKEN", valmistuminen = new LocalDate(),
          henkilo = "1.2.246.562.24.71123947024", yksilollistaminen = yksilollistaminen.Ei, suoritusKieli = "FI", lahde = "1.2.246.562.10.1234", vahv = false)
        //("1.2.246.562.24.71123947024", true, "koski") //val henkiloOid: String, val vahvistettu: Boolean, val source: String
        //Future.failed(new RuntimeException("test")) pipeTo sender
        Future.successful(Seq()) pipeTo sender
    }
  }
}
