package fi.vm.sade.hakurekisteri.integration.koski

import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.testkit.{CallingThreadDispatcher, TestActorRef, TestActors}
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.{MockConfig, Oids}
import fi.vm.sade.hakurekisteri.arvosana._
import fi.vm.sade.hakurekisteri.integration.henkilo.{IOppijaNumeroRekisteri, MockPersonAliasesProvider, PersonOidsWithAliases}
import fi.vm.sade.hakurekisteri.integration.koski.KoskiDataHandler._
import fi.vm.sade.hakurekisteri.suoritus._
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import fi.vm.sade.hakurekisteri.rest.support.JDBCJournal
import fi.vm.sade.hakurekisteri.tools.ItPostgres
import hakurekisteri.perusopetus.Yksilollistetty
import org.joda.time.{LocalDate, LocalDateTime}
import org.joda.time.format.{DateTimeFormat, DateTimeFormatter}
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.mockito
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest.concurrent.AsyncAssertions
import org.scalatest.mock.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FlatSpec, Matchers}
import support.{BareRegisters, DbJournals, PersonAliasesProvider}

import scala.compat.Platform
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, ExecutionContext, Future}

class KoskiDataHandlerTest extends FlatSpec with BeforeAndAfterEach with BeforeAndAfterAll with Matchers with MockitoSugar with AsyncAssertions {

  implicit val formats = org.json4s.DefaultFormats

  private val jsonDir = "src/test/scala/fi/vm/sade/hakurekisteri/integration/koski/json/"
  private implicit val database = Database.forURL(ItPostgres.getEndpointURL)
  private implicit val system = ActorSystem("test-jdbc")
  private implicit val ec: ExecutionContext = system.dispatcher
  private implicit val timeout: Timeout = Timeout(30, TimeUnit.SECONDS)
  private val config: MockConfig = new MockConfig
  private val journals: DbJournals = new DbJournals(config)

  private val oppijaNumeroRekisteri: IOppijaNumeroRekisteri = mock[IOppijaNumeroRekisteri]
  private val personAliasesProvider: PersonAliasesProvider = new PersonAliasesProvider {
    override def enrichWithAliases(henkiloOids: Set[String]): Future[PersonOidsWithAliases] = {
      if (henkiloOids.isEmpty) {
        Future.successful(PersonOidsWithAliases(Set(), Map()))
      } else {
        oppijaNumeroRekisteri.enrichWithAliases(henkiloOids)
      }
    }
  }
  private val rekisterit: BareRegisters = new BareRegisters(system, journals, database, personAliasesProvider)
  val suoritusJournal = new JDBCJournal[Suoritus, UUID, SuoritusTable](TableQuery[SuoritusTable])
  val suoritusrekisteri = system.actorOf(Props(new SuoritusJDBCActor(suoritusJournal, 1, MockPersonAliasesProvider)))

  val KoskiArvosanaTrigger: KoskiDataHandler = new KoskiDataHandler(suoritusrekisteri, rekisterit.arvosanaRekisteri, rekisterit.opiskelijaRekisteri)
  val suoritusParser = new KoskiSuoritusArvosanaParser
  
  override protected def beforeEach(): Unit = {
    ItPostgres.reset()
  }

  override protected def afterAll(): Unit = {
    Await.result(system.terminate(), 15.seconds)
    database.close()
  }

  it should "resolve latest opiskeluoikeudes" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "koskidata_a_lot_of_stuff.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    val henkiloOid: String = henkilo.henkilö.oid.toString

    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    henkilo.opiskeluoikeudet.size should equal (6) //Sisältää kuusi eri opiskeluoikeutta, joista yksi ammatillinen, kaksi lukiota ja kolme perusopetusta.

    val filteredOikeudes = KoskiArvosanaTrigger.ensureAinoastaanViimeisinOpiskeluoikeusJokaisestaTyypista(henkilo.opiskeluoikeudet)

    filteredOikeudes.size should equal (3)

    //Syöte-jsonissa on kolme eri peruskoulun opiskeluoikeutta, joista yhdessä on vain kasiluokan suoritus. Sillä on kuitenkin myöhäisin alkupäivä.
    //Tarkistetaan, ettei sitä ole valittu viimeisimmäksi.
    filteredOikeudes.exists(o => o.oppilaitos.get.oid.contains("1.2.246.562.10.14613773812")) should not be true

  }

  it should "parse a koski henkilo" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "testikiira.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    val result = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo).head

    val ysit = getYsiluokat(result)
    val suoritusA = result.head
    val suoritusB = result(1)

    val expectedDate = new LocalDate(2017,8,1)
    suoritusB.lasnadate should equal (expectedDate)

    getPerusopetusPäättötodistus(result).get.luokka shouldEqual "9A"
    getYsiluokat(result).head.luokka shouldEqual "9A"

    val oidsWithAliases = PersonOidsWithAliases(Set("1.2.246.562.24.71123947024"), Map.empty)
    println("great success")

  }

  it should "parse 7 course LUVA data" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "LUVA.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi shouldEqual Some(KoskiKoodi("luva", "opiskeluoikeudentyyppi"))
    val result = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo).head
    result should have length 1
    val suoritus = result.head
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

    val numcourses: Int = suoritusParser.getNumberOfAcceptedLuvaCourses(henkilo.opiskeluoikeudet.head.suoritukset.head.osasuoritukset)
    numcourses shouldBe 23
    val result = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo).head
    result should have length 1
    val suoritus = result.head
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

    val numcourses: Int = suoritusParser.getNumberOfAcceptedLuvaCourses(henkilo.opiskeluoikeudet.head.suoritukset.head.osasuoritukset)
    numcourses shouldBe 23
    val result = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo).head
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

    val numcourses: Int = suoritusParser.getNumberOfAcceptedLuvaCourses(henkilo.opiskeluoikeudet.head.suoritukset.head.osasuoritukset)
    numcourses shouldBe 25
    val result = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo).head
    result should have length 1
    val suoritus = result.head
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
    val result = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo).head
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
    val result = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo).head
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
    val result = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo).head
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
    val result = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo).head
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

    val result = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo).head
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

    val result = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo).head
    result should have length 3
    val pt = getPerusopetusPäättötodistus(result)
    pt shouldBe None
  }

  it should "parse peruskoulu_9_luokka_päättötodistus_ei_vahvistusta_yksi_nelonen.json data" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "peruskoulu_9_luokka_päättötodistus_ei_vahvistusta_yksi_nelonen.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo.opiskeluoikeudet.filter(_.tyyppi.get.koodiarvo.contentEquals("perusopetuksenoppimaara"))
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    val result = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo).head
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

    val result = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo).head


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

    val result2 = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo).head
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
    val result = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo).head
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
    val result = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo).head
    result should have length 4
    getPerusopetusPäättötodistus(result).get.luokka shouldEqual "9C"
    result(3).arvosanat should have length 18
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

    Await.result(KoskiArvosanaTrigger.processHenkilonTiedotKoskesta(henkilo,PersonOidsWithAliases(henkilo.henkilö.oid.toSet), new KoskiSuoritusHakuParams(true, true)), 5.seconds)
    val result = run(database.run(sql"select count(*) from arvosana".as[String]))
    result.head.toInt should equal(0)
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
    val result = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo).head
    //val result = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo, createLukioArvosanat = true).head
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
    val result = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo).head
    //val result = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo, createLukioArvosanat = true).head
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

  //todo varmista, että tämä testi on rakennettu oikein ja mittaa oikeaa asiaa. Hajosi kun haluttiin vain myöhäisempi kahdesta saman tason opiskeluoikeudesta.
/*  it should "parse BUG-1711.json" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "BUG-1711.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    val resultGroup = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo)
    resultGroup.head should have length 2 //kaksi opiskeluoikeutta joissa molemmissa yksi luokkatieto -> neljä suoritusarvosanaa
    //resultGroup(1) should have length 2 //kaksi opiskeluoikeutta joissa molemmissa yksi luokkatieto -> neljä suoritusarvosanaa

    resultGroup(0)(0).luokka shouldEqual "SHKK"

    val system = ActorSystem("MySpec")
    val a = system.actorOf(Props(new TestSureActor()).withDispatcher(CallingThreadDispatcher.Id))
    val oidsWithAliases = PersonOidsWithAliases(Set("1.2.246.562.24.10101010101"), Map.empty)
    println("great success")
  }*/

  it should "parse ammu_heluna.json" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "ammu_heluna.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    val resultGroup = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo)
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
    val resultGroup = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo)
    resultGroup should have length 0
    //resultGroup.head should have length 0
  }

  it should "test data jäänyt_luokalle_peruskoulu.json" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "jäänyt_luokalle_peruskoulu.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    val resultGroup = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo)
    resultGroup should have length 1
    resultGroup.head should have length 3
    getPerusopetusPäättötodistus(resultGroup.head).get.luokka shouldEqual "9C"
  }

  it should "parse 1.2.246.562.24.40546864498.json" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "1.2.246.562.24.40546864498.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    val resultGroup = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo)
    resultGroup should have length 1
    resultGroup.head should have length 2

    val arvosanat = resultGroup.head
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
    val resultGroup = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo)
    resultGroup should have length 2
    resultGroup.last should have length 1
    val suoritusarvosanat = resultGroup.last
    suoritusarvosanat should have length 1
    val suoritusarvosana = suoritusarvosanat.head
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

    val resultGroup = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo)
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

    val res = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo)

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

    val res = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo)

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

    val res = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo)
    res should have length 1
    val arvosanat = res.head
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


    val seq = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo)
    seq should have length 2

    val res = seq(1)
    res should have length 0
    /*
    val suor: SuoritusArvosanat = res.head

    suor.suoritus should not be null
    val virallinen = suor.suoritus.asInstanceOf[VirallinenSuoritus]
    virallinen.komo shouldEqual Oids.lisaopetusKomoOid
    virallinen.tila shouldEqual "KESKEN"
*/
  }

  it should "parse luokkatietoinen-testi.json" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "luokkatietoinen-testi.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    val resgroup = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo)
    resgroup should have length 1

    val res = resgroup.head
    res should have length 3

    res.exists(_.luokka.contentEquals("9A")) shouldEqual true
    res.exists(_.luokka.contentEquals("9D")) shouldEqual true

    val luokkatieto = res.filter(_.luokka.contentEquals("9D")).head
    val virallinensuoritus = luokkatieto.suoritus.asInstanceOf[VirallinenSuoritus]
    virallinensuoritus.tila shouldEqual "KESKEN"

    val päättötodistus = res.filter(_.suoritus.asInstanceOf[VirallinenSuoritus].komo.contentEquals(Oids.perusopetusKomoOid)).head

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

    val resgroup = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo)
    resgroup should have length 1
    val pt = getPerusopetusPäättötodistus(resgroup.head).get
    val ysiluokat = getYsiluokat(resgroup.head)
    ysiluokat should have length 1
    ysiluokat.head.suoritus.asInstanceOf[VirallinenSuoritus].valmistuminen.toString("dd.MM.YYYY") shouldEqual "01.06.2017"
    pt.suoritus.asInstanceOf[VirallinenSuoritus].valmistuminen.toString("dd.MM.YYYY") shouldEqual "01.06.2017"

  }

  it should "get correct end date from last ysiluokka" in {
    val koskikomo = KoskiKoulutusmoduuli(None, None, None, None, None)

    val vahvistus = KoskiVahvistus("2000-04-01", KoskiOrganisaatio(Some("")))
    val vahvistus2 = KoskiVahvistus("2000-05-03", KoskiOrganisaatio(Some("")))
    val vahvistus3 = KoskiVahvistus("2000-05-02", KoskiOrganisaatio(Some("")))

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
    val maybedate: Option[LocalDate] = suoritusParser.getEndDateFromLastNinthGrade(suoritukset)

    maybedate.get shouldEqual parseLocalDate("2000-05-03")

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

  it should "filter valinnaiset aineet from aikuisten_perusopetus_valinnaiset2.json" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "aikuisten_perusopetus_valinnaiset2.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]

    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    val resultgroup = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo)
    resultgroup should have length 1
    val result: Seq[SuoritusArvosanat] = resultgroup.head
    result should have length 1

    result.head.arvosanat.filter(_.aine.contentEquals("B2")).head.valinnainen shouldEqual false
    result.head.arvosanat.filter(_.aine.contentEquals("A2")).head.valinnainen shouldEqual false

    val valinnaisetAineet = result.head.arvosanat.filter(_.valinnainen == true).map(_.aine)
    valinnaisetAineet shouldNot contain("KU")
    valinnaisetAineet should contain("AI")
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

    val resgroup = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo)
    //val resgroup = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo, createLukioArvosanat = true)
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

  it should "interpret A2 and B2 langs as pakollinen in lisäopetus" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "A2B2ValinnaisetPakollisina_lisäopetus.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]

    val res: Seq[Seq[SuoritusArvosanat]] = KoskiArvosanaTrigger.createSuorituksetJaArvosanatFromKoski(henkilo)
    res should have length 2

    val perusopetus = res.head
    val lisäopetus = res(1)

    val aineet = Set("A2", "B2")
    val perusA2B2 = getPerusopetusPäättötodistus(perusopetus).get.arvosanat.filter(a => aineet.contains(a.aine))
    perusA2B2 should have length 0

    val lisäA2B2 = lisäopetus.flatMap(_.arvosanat.filter(a => aineet.contains(a.aine)))
    lisäA2B2 should have length 2
    lisäA2B2.map(_.valinnainen) shouldEqual Seq(false, false)
  }

  it should "not set osasuoritus as yksilöllistetty if not pakollinen" in {
    var json: String = scala.io.Source.fromFile(jsonDir + "yksilöllistäminen.json").mkString
    var henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    var osasuoritukset: Seq[KoskiOsasuoritus] = henkilo.opiskeluoikeudet.head.suoritukset.head.osasuoritukset

    /*
    Kaikki pakolliset eivät ole yksilöllistettyjä, ei-pakolliset ovat -> Ei
    1: pakollinen kyllä, yksilöllistetty ei
    2: pakollinen kyllä, yksilöllistetty ei
    3: pakollinen ei, yksilöllistetty kyllä
    4: pakollinen ei, yksilöllistetty kyllä
    */
    var result = suoritusParser.osasuoritusToArvosana(henkilo.henkilö.oid.get, "TEST", osasuoritukset, None, None, false, LocalDate.now())
    result._2 should equal (yksilollistaminen.Ei)

    json  = scala.io.Source.fromFile(jsonDir + "yksilöllistäminen1.json").mkString
    henkilo = parse(json).extract[KoskiHenkiloContainer]
    osasuoritukset = henkilo.opiskeluoikeudet.head.suoritukset.head.osasuoritukset

    /*
    Yksi pakollinen yksilöllistetty -> Osittain
    1: pakollinen kyllä, yksilöllistetty ei
    2: pakollinen kyllä, yksilöllistetty ei
    3: pakollinen kyllä, yksilöllistetty kyllä
    4: pakollinen ei, yksilöllistetty kyllä
    */
    result = suoritusParser.osasuoritusToArvosana(henkilo.henkilö.oid.get, "TEST", osasuoritukset, None, None, false, LocalDate.now())
    result._2 should equal (yksilollistaminen.Osittain)

    json  = scala.io.Source.fromFile(jsonDir + "yksilöllistäminen2.json").mkString
    henkilo = parse(json).extract[KoskiHenkiloContainer]
    osasuoritukset = henkilo.opiskeluoikeudet.head.suoritukset.head.osasuoritukset

    /*
    Yksi pakollinen ja yksilöllistetty -> Kokonaan
    1: pakollinen ei, yksilöllistetty ei
    2: pakollinen ei, yksilöllistetty ei
    3: pakollinen kyllä, yksilöllistetty kyllä
    4: pakollinen ei, yksilöllistetty ei
    */
    result = suoritusParser.osasuoritusToArvosana(henkilo.henkilö.oid.get, "TEST", osasuoritukset, None, None, false, LocalDate.now())
    result._2 should equal (yksilollistaminen.Kokonaan)
  }

  it should "store suoritus & arvosana if no overlapping opiskeluoikeus found" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "viimeisin_opiskeluoikeus.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    val henkiloOid: String = henkilo.henkilö.oid.toString

    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    val params: KoskiSuoritusHakuParams = new KoskiSuoritusHakuParams(false, true)
    Await.result(KoskiArvosanaTrigger.processHenkilonTiedotKoskesta(henkilo,PersonOidsWithAliases(henkilo.henkilö.oid.toSet), params), 5.seconds)
    val opiskelijat = run(database.run(sql"select henkilo_oid from opiskelija".as[String]))
    opiskelijat.size should equal(1)
    opiskelijat.head should equal("1.2.246.562.24.32656706483")
    val opiskelija = opiskelijat.head
    val suoritukset = run(database.run(sql"select resource_id from suoritus where henkilo_oid = $opiskelija".as[String]))
    suoritukset should have length 1
    val suoritus = suoritukset.head
    val arvosanat = run(database.run(sql"select * from arvosana where suoritus = $suoritus".as[String]))
    arvosanat should have length 6
  }

  it should "resolve latest perusopetuksen lasnaoleva opiskeluoikeus" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "koskidata_1pk_1amm.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    val henkiloOid: String = henkilo.henkilö.oid.toString

    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty


    Await.result(KoskiArvosanaTrigger.processHenkilonTiedotKoskesta(henkilo,PersonOidsWithAliases(henkilo.henkilö.oid.toSet), new KoskiSuoritusHakuParams(false, true)), 5.seconds)
    var suoritukset = run(database.run(sql"select myontaja from suoritus where komo = '1.2.246.562.13.62959769647'".as[String]))
    suoritukset.size should equal(1)
    var myontaja = suoritukset.head
    myontaja should equal("1.2.246.562.10.33327422946")
    suoritukset = run(database.run(sql"select resource_id from suoritus where komo = '1.2.246.562.13.62959769647'".as[String]))
    var suoritus = suoritukset.head.toString
    var arvosanat = run(database.run(sql"select * from arvosana where suoritus = $suoritus".as[String]))
    arvosanat should have length 18
  }

  it should "delete opiskelija, suoritus and arvosanat not existing in koski anymore" in {
    var json: String = scala.io.Source.fromFile(jsonDir + "koskidata_1pk_1amm.json").mkString
    var henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    val henkiloOid: String = henkilo.henkilö.oid.get.toString

    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    Await.result(KoskiArvosanaTrigger.processHenkilonTiedotKoskesta(henkilo,PersonOidsWithAliases(henkilo.henkilö.oid.toSet), new KoskiSuoritusHakuParams(false, true)), 5.seconds)
    var opiskelijat1 = run(database.run(sql"select henkilo_oid from opiskelija where deleted = false and henkilo_oid = $henkiloOid".as[String]))
    opiskelijat1.size should equal(2)
    var opiskelija1 = opiskelijat1.head
    var suoritukset1 = run(database.run(sql"select resource_id from suoritus where deleted = false and current = true and henkilo_oid = $opiskelija1".as[String]))
    suoritukset1.size should equal(2)
    var suoritus1 = suoritukset1.head
    var arvosanat1 = run(database.run(sql"select * from arvosana where deleted = false and current = true and suoritus = $suoritus1".as[String]))
    arvosanat1 should have length 18

    json = scala.io.Source.fromFile(jsonDir + "koskidata_1amm.json").mkString
    henkilo = parse(json).extract[KoskiHenkiloContainer]

    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    Await.result(KoskiArvosanaTrigger.processHenkilonTiedotKoskesta(henkilo,PersonOidsWithAliases(henkilo.henkilö.oid.toSet), new KoskiSuoritusHakuParams(false, true)), 5.seconds)

    var opiskelijat2 = run(database.run(sql"select henkilo_oid from opiskelija where deleted = false and current = true and henkilo_oid = $henkiloOid".as[String]))
    opiskelijat2.size should equal(1)
    var opiskelija2 = opiskelijat2.head
    var suoritukset2 = run(database.run(sql"select resource_id from suoritus where deleted = false and current = 'true' and komo = 'ammatillinentutkinto komo oid' and henkilo_oid = $opiskelija2".as[String]))
    suoritukset2.size should equal(1)
    var suoritus2 = suoritukset2.head
    var arvosanat2 = run(database.run(sql"select * from arvosana where deleted = false and current = true and suoritus = $suoritus2".as[String]))
    arvosanat2 should have length 0


    json = scala.io.Source.fromFile(jsonDir + "koskidata_lukio.json").mkString
    henkilo = parse(json).extract[KoskiHenkiloContainer]

    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    Await.result(KoskiArvosanaTrigger.processHenkilonTiedotKoskesta(henkilo,PersonOidsWithAliases(henkilo.henkilö.oid.toSet), new KoskiSuoritusHakuParams(true, true)), 5.seconds)
    var opiskelijat3 = run(database.run(sql"select henkilo_oid from opiskelija where deleted = false and current = true and henkilo_oid = $henkiloOid".as[String]))
    opiskelijat3.size should equal(1)
    var opiskelija3 = opiskelijat3.head
    var suoritukset3 = run(database.run(sql"select resource_id from suoritus where deleted = false and current = 'true' and komo = 'TODO lukio komo oid' and henkilo_oid = $opiskelija3".as[String]))
    suoritukset3.size should equal(1)
    var suoritus3 = suoritukset3.head
    var arvosanat3 = run(database.run(sql"select * from arvosana where deleted = false and current = true and suoritus = $suoritus3".as[String]))
    arvosanat3 should have length 4
  }

  it should "store suoritus & set valmistumispäivä to third of june if suoritus kesken" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "koskidata_valmistumispäivä.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    val henkiloOid: String = henkilo.henkilö.oid.toString
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    Await.result(KoskiArvosanaTrigger.processHenkilonTiedotKoskesta(henkilo,PersonOidsWithAliases(henkilo.henkilö.oid.toSet), new KoskiSuoritusHakuParams(true, true)), 5.seconds)
    val opiskelijat = run(database.run(sql"select henkilo_oid from opiskelija".as[String]))
    opiskelijat.size should equal(1)
    val opiskelija = opiskelijat.head
    val valmistuminen = run(database.run(sql"select valmistuminen from suoritus where henkilo_oid = $opiskelija".as[String]))
    valmistuminen should have length 1
    valmistuminen.head should equal(KoskiUtil.deadlineDate.toString())
  }

  it should "Not delete opiskelija, suoritus and arvosanat if source is not koski" in {
    var json: String = scala.io.Source.fromFile(jsonDir + "koskidata_1pk_1amm.json").mkString
    var henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    val henkiloOid: String = henkilo.henkilö.oid.get.toString

    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    Await.result(KoskiArvosanaTrigger.processHenkilonTiedotKoskesta(henkilo,PersonOidsWithAliases(henkilo.henkilö.oid.toSet), new KoskiSuoritusHakuParams(false, true)), 5.seconds)
    var opiskelijat1 = run(database.run(sql"select henkilo_oid from opiskelija where deleted = false and henkilo_oid = $henkiloOid".as[String]))
    opiskelijat1.size should equal(2)
    var opiskelija1 = opiskelijat1.head
    var suoritukset1 = run(database.run(sql"select resource_id from suoritus where deleted = false and current = true and henkilo_oid = $opiskelija1".as[String]))
    suoritukset1.size should equal(2)
    var suoritus1 = suoritukset1.head
    var arvosanat1 = run(database.run(sql"select * from arvosana where deleted = false and current = true and suoritus = $suoritus1".as[String]))
    arvosanat1 should have length 18

    //Päivitetään peruskoulusuoritus ei koskesta tulleeksi:
    run(database.run(sql"update opiskelija set source = '1.2.246.562.24.35939175712' where oppilaitos_oid = '1.2.246.562.10.33327422946'".as[String]))
    run(database.run(sql"update suoritus set source = '1.2.246.562.24.35939175712' where myontaja = '1.2.246.562.10.33327422946'".as[String]))
    run(database.run(sql"update arvosana set source = '1.2.246.562.24.35939175712' where source = 'koski'".as[String]))

    opiskelijat1 = run(database.run(sql"select henkilo_oid from opiskelija where deleted = false and source = '1.2.246.562.24.35939175712' and henkilo_oid = $henkiloOid".as[String]))
    opiskelijat1.size should equal(1)
    opiskelija1 = opiskelijat1.head
    suoritukset1 = run(database.run(sql"select resource_id from suoritus where deleted = false and source = '1.2.246.562.24.35939175712' and current = true and henkilo_oid = $opiskelija1".as[String]))
    suoritukset1.size should equal(1)
    suoritus1 = suoritukset1.head
    arvosanat1 = run(database.run(sql"select * from arvosana where deleted = false and suoritus = $suoritus1 and current = true".as[String]))
    arvosanat1 should have length 18

    json = scala.io.Source.fromFile(jsonDir + "koskidata_lukio.json").mkString
    henkilo = parse(json).extract[KoskiHenkiloContainer]

    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    Await.result(KoskiArvosanaTrigger.processHenkilonTiedotKoskesta(henkilo,PersonOidsWithAliases(henkilo.henkilö.oid.toSet), new KoskiSuoritusHakuParams(true, false)), 5.seconds)

    var opiskelijat2 = run(database.run(sql"select henkilo_oid from opiskelija where deleted = false and current = true and henkilo_oid = $henkiloOid".as[String]))
    opiskelijat2.size should equal(2)
    var opiskelija2 = opiskelijat2.head
    var suoritukset2 = run(database.run(sql"select resource_id from suoritus where deleted = false and current = 'true' and henkilo_oid = $opiskelija2".as[String]))
    suoritukset2.size should equal(2)
    suoritukset2 = run(database.run(sql"select resource_id from suoritus where deleted = false and current = 'true' and source = '1.2.246.562.24.35939175712' and henkilo_oid = $opiskelija2".as[String]))
    var suoritus2 = suoritukset2.head
    var arvosanat2 = run(database.run(sql"select * from arvosana where deleted = false and current = true and suoritus = $suoritus2".as[String]))
    arvosanat2 should have length 18
  }

  it should "store only peruskoulusuoritus when KoskiSuoritusHakuParams.saveAmmatillinen is false" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "koskidata_1pk_1amm.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    val henkiloOid: String = henkilo.henkilö.oid.toString
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    Await.result(KoskiArvosanaTrigger.processHenkilonTiedotKoskesta(henkilo,PersonOidsWithAliases(henkilo.henkilö.oid.toSet), new KoskiSuoritusHakuParams(true, false)), 5.seconds)
    val opiskelijat = run(database.run(sql"select henkilo_oid from opiskelija".as[String]))
    opiskelijat.size should equal(1)
    val opiskelija = opiskelijat.head
    val suoritukset = run(database.run(sql"select komo from suoritus where henkilo_oid = $opiskelija".as[String]))
    suoritukset.size should equal(1)
    suoritukset.head should equal("1.2.246.562.13.62959769647")
  }

  it should "store ammatillinen suoritus when KoskiSuoritusHakuParams.saveAmmatillinen is true" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "koskidata_1pk_1amm.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    val henkiloOid: String = henkilo.henkilö.oid.toString
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    Await.result(KoskiArvosanaTrigger.processHenkilonTiedotKoskesta(henkilo,PersonOidsWithAliases(henkilo.henkilö.oid.toSet), new KoskiSuoritusHakuParams(false, true)), 5.seconds)
    val opiskelijat = run(database.run(sql"select henkilo_oid from opiskelija".as[String]))
    opiskelijat.size should equal(2)
    val opiskelija = opiskelijat.head
    val suoritukset = run(database.run(sql"select komo from suoritus where henkilo_oid = $opiskelija".as[String]))
    suoritukset.size should equal(2)
  }

  it should "store lukiosuoritus when KoskiSuoritusHakuParams.saveLukio & KoskiSuoritusHakuParams.saveAmmatillinen is true" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "koskidata_lukio.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    val henkiloOid: String = henkilo.henkilö.oid.toString
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    Await.result(KoskiArvosanaTrigger.processHenkilonTiedotKoskesta(henkilo,PersonOidsWithAliases(henkilo.henkilö.oid.toSet), new KoskiSuoritusHakuParams(true, true)), 5.seconds)

    val opiskelijat = run(database.run(sql"select henkilo_oid from opiskelija".as[String]))
    opiskelijat.size should equal(1)
    val opiskelija = opiskelijat.head
    val suoritus = run(database.run(sql"select valmistuminen from suoritus where henkilo_oid = $opiskelija".as[String]))
    suoritus.size should equal(1)
  }

  it should "not store lukiosuoritus when KoskiSuoritusHakuParams.saveLukio & KoskiSuoritusHakuParams.saveAmmatillinen is false" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "koskidata_lukio.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    val henkiloOid: String = henkilo.henkilö.oid.toString
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    Await.result(KoskiArvosanaTrigger.processHenkilonTiedotKoskesta(henkilo,PersonOidsWithAliases(henkilo.henkilö.oid.toSet), new KoskiSuoritusHakuParams(false, false)), 5.seconds)

    val opiskelijat = run(database.run(sql"select henkilo_oid from opiskelija".as[String]))
    opiskelijat.size should equal(0)
    val suoritus = run(database.run(sql"select valmistuminen from suoritus where henkilo_oid = $henkiloOid".as[String]))
    suoritus.size should equal(0)
  }

  it should "store peruskoulu kesken with arvosanat if deadline date is yesterday" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "koskidata_peruskoulu_kesken.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    val henkiloOid: String = henkilo.henkilö.oid.toString
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    KoskiUtil.deadlineDate = LocalDate.now().minusDays(1)

    Await.result(KoskiArvosanaTrigger.processHenkilonTiedotKoskesta(henkilo,PersonOidsWithAliases(henkilo.henkilö.oid.toSet), new KoskiSuoritusHakuParams(false, false)), 5.seconds)

    val opiskelijat = run(database.run(sql"select henkilo_oid from opiskelija".as[String]))
    opiskelijat.size should equal(1)
    val suoritus = run(database.run(sql"select resource_id from suoritus".as[String]))
    suoritus.size should equal(1)

    var arvosanat = run(database.run(sql"select * from arvosana where deleted = false and current = true".as[String]))
    arvosanat should have length 17
  }

  it should "not store peruskoulu kesken with arvosanat if deadline date is tomorrow" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "koskidata_peruskoulu_kesken.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    val henkiloOid: String = henkilo.henkilö.oid.toString
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    KoskiUtil.deadlineDate = LocalDate.now().plusDays(1)

    Await.result(KoskiArvosanaTrigger.processHenkilonTiedotKoskesta(henkilo,PersonOidsWithAliases(henkilo.henkilö.oid.toSet), new KoskiSuoritusHakuParams(false, false)), 5.seconds)

    val opiskelijat = run(database.run(sql"select henkilo_oid from opiskelija".as[String]))
    opiskelijat.size should equal(1)
    val suoritus = run(database.run(sql"select resource_id from suoritus".as[String]))
    suoritus.size should equal(1)

    var arvosanat = run(database.run(sql"select * from arvosana where deleted = false and current = true".as[String]))
    arvosanat should have length 0
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
      case None => Seq.empty
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

  private def run[T](f: Future[T]): T = Await.result(f, atMost = timeout.duration)

}
