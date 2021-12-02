package fi.vm.sade.hakurekisteri.integration.koski

import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorSystem, Props}
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.{MockConfig, Oids}
import fi.vm.sade.hakurekisteri.arvosana._
import fi.vm.sade.hakurekisteri.integration.henkilo.{
  IOppijaNumeroRekisteri,
  MockPersonAliasesProvider,
  PersonOidsWithAliases
}
import fi.vm.sade.hakurekisteri.integration.koski.KoskiUtil._
import fi.vm.sade.hakurekisteri.suoritus._
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import fi.vm.sade.hakurekisteri.rest.support.JDBCJournal
import fi.vm.sade.hakurekisteri.tools.ItPostgres
import org.joda.time.LocalDate
import org.joda.time.format.DateTimeFormat
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.scalatest.concurrent.Waiters
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FlatSpec, Matchers}
import support.{BareRegisters, DbJournals, PersonAliasesProvider}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

class KoskiDataHandlerTest
    extends FlatSpec
    with BeforeAndAfterEach
    with BeforeAndAfterAll
    with Matchers
    with MockitoSugar
    with Waiters {

  implicit val formats = org.json4s.DefaultFormats

  private val jsonDir = "src/test/scala/fi/vm/sade/hakurekisteri/integration/koski/json/"
  private implicit val database = ItPostgres.getDatabase
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
  private val rekisterit: BareRegisters =
    new BareRegisters(system, journals, database, personAliasesProvider, config)
  val suoritusJournal =
    new JDBCJournal[Suoritus, UUID, SuoritusTable](TableQuery[SuoritusTable], config = config)
  val suoritusrekisteri = system.actorOf(
    Props(new SuoritusJDBCActor(suoritusJournal, 1, MockPersonAliasesProvider, config))
  )

  val koskiDatahandler: KoskiDataHandler = new KoskiDataHandler(
    suoritusrekisteri,
    rekisterit.arvosanaRekisteri,
    rekisterit.opiskelijaRekisteri
  )
  val koskiOpiskelijaParser = new KoskiOpiskelijaParser
  val koskiSuoritusParser = new KoskiSuoritusArvosanaParser

  override protected def beforeEach(): Unit = {
    ItPostgres.reset()
  }

  override protected def afterAll(): Unit = {
    Await.result(system.terminate(), 15.seconds)
    database.close()
  }

  it should "resolve latest lasnaolodate from koskiopiskeluoikeusjakso" in {
    var koskiTila1 = KoskiTila("2019-01-01", KoskiKoodi("loma", ""))
    var koskiTila2 = KoskiTila("2019-02-02", KoskiKoodi("lasna", ""))
    var koskiTila3 = KoskiTila("2019-03-03", KoskiKoodi("loma", ""))
    var koskiTila4 = KoskiTila("2019-04-04", KoskiKoodi("lasna", ""))

    var koskiOpiskeluoikeusjakso =
      KoskiOpiskeluoikeusjakso(Seq(koskiTila1, koskiTila2, koskiTila3, koskiTila4))
    koskiOpiskeluoikeusjakso.findEarliestLasnaDate should equal(Some(new LocalDate("2019-02-02")))

    koskiTila1 = KoskiTila("2019-01-01", KoskiKoodi("loma", ""))
    koskiTila2 = KoskiTila("2019-02-02", KoskiKoodi("loma", ""))
    koskiTila3 = KoskiTila("2019-03-03", KoskiKoodi("loma", ""))
    koskiTila4 = KoskiTila("2019-04-04", KoskiKoodi("loma", ""))

    koskiOpiskeluoikeusjakso =
      KoskiOpiskeluoikeusjakso(Seq(koskiTila1, koskiTila2, koskiTila3, koskiTila4))
    koskiOpiskeluoikeusjakso.findEarliestLasnaDate should equal(None)
  }

  it should "resolve latest opiskeluoikeudes" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "koskidata_a_lot_of_stuff.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]

    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    henkilo.opiskeluoikeudet.size should equal(
      7
    ) //Sisältää 7 eri opiskeluoikeutta, joista kaksi ammatillista, kaksi lukiota ja kolme perusopetusta.

    val filteredOikeudes = koskiDatahandler.halututOpiskeluoikeudetJaSuoritukset(
      henkilo.henkilö.oid.get,
      henkilo.opiskeluoikeudet
    )

    filteredOikeudes.size should equal(4)

    //Syöte-jsonissa on kolme eri peruskoulun opiskeluoikeutta, joista yhdessä on vain kasiluokan suoritus. Sillä on kuitenkin myöhäisin alkupäivä.
    //Tarkistetaan, ettei sitä ole valittu viimeisimmäksi.
    filteredOikeudes.exists(o =>
      o.oppilaitos.get.oid.contains("1.2.246.562.10.14613773812")
    ) should not be true

  }

  it should "parse a koski henkilo" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "testikiira.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    val result = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo).head

    val ysit = getYsiluokat(result)
    val suoritusA = result.head
    val suoritusB = result(1)

    val expectedDate = new LocalDate(2017, 8, 1)
    suoritusB.lasnadate should equal(expectedDate)

    getPerusopetusPäättötodistus(result).get.luokka shouldEqual "9A"
    getYsiluokat(result).head.luokka shouldEqual "9A"

    val oidsWithAliases = PersonOidsWithAliases(Set("1.2.246.562.24.71123947024"), Map.empty)
    println("great success")

  }

  it should "parse 7 course LUVA data as kesken before deadline date" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "LUVA.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi shouldEqual Some(
      KoskiKoodi("luva", "opiskeluoikeudentyyppi")
    )

    KoskiUtil.deadlineDate = LocalDate.now().plusDays(1)

    val result = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo).head
    result should have length 1
    val virallinen = result.head.suoritus
    virallinen.tila should equal("KESKEN")
    virallinen.komo should equal(Oids.lukioonvalmistavaKomoOid)
  }

  it should "parse 7 course LUVA data as keskeytynyt after deadline date" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "LUVA.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi shouldEqual Some(
      KoskiKoodi("luva", "opiskeluoikeudentyyppi")
    )

    KoskiUtil.deadlineDate = LocalDate.now().minusDays(1)

    val result = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo).head
    result should have length 1
    val virallinen = result.head.suoritus
    virallinen.tila should equal("KESKEYTYNYT")
    virallinen.komo should equal(Oids.lukioonvalmistavaKomoOid)
  }

  it should "parse 23 course LUVA data as kesken before deadlinedate" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "LUVA_23_kurssia.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    KoskiUtil.deadlineDate = LocalDate.now().plusDays(1)

    val numcourses: Int = koskiSuoritusParser.getNumberOfAcceptedLuvaCourses(
      henkilo.opiskeluoikeudet.head.suoritukset.head.osasuoritukset
    )
    numcourses shouldBe 23
    val result = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo).head
    result should have length 1
    val virallinen = result.head.suoritus
    virallinen.tila should equal("KESKEN")
    virallinen.komo should equal(Oids.lukioonvalmistavaKomoOid)
  }

  it should "parse 23 course LUVA data as keskeytynyt after deadlinedate" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "LUVA_23_kurssia.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    KoskiUtil.deadlineDate = LocalDate.now().minusDays(1)

    val numcourses: Int = koskiSuoritusParser.getNumberOfAcceptedLuvaCourses(
      henkilo.opiskeluoikeudet.head.suoritukset.head.osasuoritukset
    )
    numcourses shouldBe 23
    val result = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo).head
    result should have length 1
    val virallinen = result.head.suoritus
    virallinen.tila should equal("KESKEYTYNYT")
    virallinen.komo should equal(Oids.lukioonvalmistavaKomoOid)
  }

  it should "parse vahvistettu 23 course LUVA data as kesken before deadline" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "LUVA_23_kurssia_vahvistettu.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    KoskiUtil.deadlineDate = LocalDate.now().plusDays(1)

    val numcourses: Int = koskiSuoritusParser.getNumberOfAcceptedLuvaCourses(
      henkilo.opiskeluoikeudet.head.suoritukset.head.osasuoritukset
    )
    numcourses shouldBe 23
    val result = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo).head
    result should have length 1
    val virallinen = result.head.suoritus
    virallinen.tila should equal("KESKEN")
    virallinen.komo should equal(Oids.lukioonvalmistavaKomoOid)
  }

  it should "parse vahvistettu 23 course LUVA data as keskeytynyt after deadline" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "LUVA_23_kurssia_vahvistettu.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    KoskiUtil.deadlineDate = LocalDate.now().minusDays(1)

    val numcourses: Int = koskiSuoritusParser.getNumberOfAcceptedLuvaCourses(
      henkilo.opiskeluoikeudet.head.suoritukset.head.osasuoritukset
    )
    numcourses shouldBe 23
    val result = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo).head
    result should have length 1
    val virallinen = result.head.suoritus
    virallinen.tila should equal("KESKEYTYNYT")
    virallinen.komo should equal(Oids.lukioonvalmistavaKomoOid)
  }

  it should "parse 25 course LUVA data" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "LUVA_25_kurssia.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    val numcourses: Int = koskiSuoritusParser.getNumberOfAcceptedLuvaCourses(
      henkilo.opiskeluoikeudet.head.suoritukset.head.osasuoritukset
    )
    numcourses shouldBe 25
    val result = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo).head
    result should have length 1
    val virallinen = result.head.suoritus
    virallinen.tila should equal("VALMIS")
    virallinen.komo should equal(Oids.lukioonvalmistavaKomoOid)
  }

  it should "parse VALMA data" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "VALMA.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi shouldEqual Some(
      KoskiKoodi("ammatillinenkoulutus", "opiskeluoikeudentyyppi")
    )
    val result = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo).head
    result should have length 1
    val virallinen = result.head.suoritus

    //VALMA arvosanas should not be saved
    result.head.arvosanat should have length 0
    virallinen.tila should equal("VALMIS")
    virallinen.komo should equal(Oids.valmaKomoOid)
  }

  /*
  //TODO is the test data valid???
  it should "parse peruskoulu_lisäopetus_ei_vahvistettu.json data" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "peruskoulu_lisäopetus_ei_vahvistettu.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    val result: Seq[SuoritusArvosanat] = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo).head
    result should have length 1
    result.head.suoritus.tila should equal("KESKEYTYNYT")
  }
   */
  it should "parse peruskoulu_lisäopetus.json data" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "peruskoulu_lisäopetus.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    val result = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo).head
    result should have length 1
    result.head.suoritus.tila should equal("VALMIS")
  }

  it should "parse peruskoulu_lisäopetus.json arvosanat" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "peruskoulu_lisäopetus.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    val result = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo).head
    result should have length 1
    result.head.suoritus.tila should equal("VALMIS")
    result.head.arvosanat should have length 13
  }

  it should "parse peruskoulu_9_luokka_päättötodistus_jää_luokalle.json data as keskeytynyt after deadline" in {
    val json: String = scala.io.Source
      .fromFile(jsonDir + "peruskoulu_9_luokka_päättötodistus_jää_luokalle.json")
      .mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    henkilo.opiskeluoikeudet.head.suoritukset(2).jääLuokalle shouldEqual Some(true)

    KoskiUtil.deadlineDate = LocalDate.now().minusDays(1)

    val result = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo).head
    result should have length 4

    result(2).arvosanat should have length 0

    val paattotodistus = result(3)
    getPerusopetusPäättötodistus(result).get.luokka shouldEqual "9C"

    val virallinenpaattotodistus = paattotodistus.suoritus
    virallinenpaattotodistus.komo shouldNot be("luokka")
    paattotodistus.arvosanat should have length 0
    virallinenpaattotodistus.tila should equal("KESKEYTYNYT")

    peruskouluB2KieletShouldNotBeValinnainen(result)
  }

  it should "parse peruskoulu_9_luokka_päättötodistus_jää_luokalle.json data as keskeytynyt if set jää luokalle in koski before deadline" in {
    val json: String = scala.io.Source
      .fromFile(jsonDir + "peruskoulu_9_luokka_päättötodistus_jää_luokalle.json")
      .mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    henkilo.opiskeluoikeudet.head.suoritukset(2).jääLuokalle shouldEqual Some(true)

    KoskiUtil.deadlineDate = LocalDate.now().plusDays(1)

    val result = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo).head
    result should have length 4

    result(2).arvosanat should have length 0

    val paattotodistus = result(3)
    getPerusopetusPäättötodistus(result).get.luokka shouldEqual "9C"

    val virallinenpaattotodistus = paattotodistus.suoritus
    virallinenpaattotodistus.komo shouldNot be("luokka")
    paattotodistus.arvosanat should have length 0
    virallinenpaattotodistus.tila should equal("KESKEYTYNYT")

    peruskouluB2KieletShouldNotBeValinnainen(result)
  }

  it should "parse peruskoulu_9_luokka_päättötodistus_ei_vahvistusta.json data" in {
    val json: String = scala.io.Source
      .fromFile(jsonDir + "peruskoulu_9_luokka_päättötodistus_ei_vahvistusta.json")
      .mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    KoskiUtil.deadlineDate = LocalDate.now().minusDays(1)

    val result = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo).head
    result should have length 4
    val pt = getPerusopetusPäättötodistus(result)
    pt.get.suoritus.tila shouldBe "KESKEYTYNYT"
  }

  it should "parse peruskoulu_9_luokka_päättötodistus_ei_vahvistusta_yksi_nelonen.json data" in {
    val json: String = scala.io.Source
      .fromFile(jsonDir + "peruskoulu_9_luokka_päättötodistus_ei_vahvistusta_yksi_nelonen.json")
      .mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo.opiskeluoikeudet.filter(_.tyyppi.get.koodiarvo.contentEquals("perusopetuksenoppimaara"))
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    KoskiUtil.deadlineDate = LocalDate.now().minusDays(1)

    val result = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo).head
    result should have length 4
    val pt = getPerusopetusPäättötodistus(result).get
    pt.suoritus.tila shouldEqual "VALMIS"
    pt.arvosanat should have length 18
  }

  it should "parse peruskoulu_9_luokka_päättötodistus_vuosiluokkiinSitoutumatonOpetus_true.json data" in {
    val json: String = scala.io.Source
      .fromFile(
        jsonDir + "peruskoulu_9_luokka_päättötodistus_vuosiluokkiinSitoutumatonOpetus_true.json"
      )
      .mkString
    val henkiloList: List[KoskiHenkiloContainer] = parse(json).extract[List[KoskiHenkiloContainer]]
    val henkilo = henkiloList.head
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    val lisätiedot = henkilo.opiskeluoikeudet.head.lisätiedot
    lisätiedot shouldBe defined
    lisätiedot.get.vuosiluokkiinSitoutumatonOpetus should be(Some(true))

    val result = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo).head

    peruskouluB2KieletShouldNotBeValinnainen(result)

    result should have length 4
    getPerusopetusPäättötodistus(result).get.luokka shouldEqual "9C"
    result(2).suoritus.tila should equal("VALMIS")

    val henkilo2 = henkiloList(1)
    henkilo2 should not be null
    val lisätiedot2 = henkilo2.opiskeluoikeudet.head.lisätiedot
    lisätiedot2 shouldBe defined
    lisätiedot2.get.vuosiluokkiinSitoutumatonOpetus should be(Some(true))

    val result2 = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo2).head
    result2 should have length 2
  }

  it should "parse when vuosiluokkiinSitoutumatonOpetus == true and tila != valmis data" in {
    val json: String = scala.io.Source
      .fromFile(
        jsonDir + "peruskoulu_9_luokka_päättötodistus_vuosiluokkiinSitoutumatonOpetus_true_vahvistus_false.json"
      )
      .mkString
    val henkiloList: List[KoskiHenkiloContainer] = parse(json).extract[List[KoskiHenkiloContainer]]
    val henkilo = henkiloList.head
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    val lisätiedot = henkilo.opiskeluoikeudet.head.lisätiedot
    lisätiedot shouldBe defined
    lisätiedot.get.vuosiluokkiinSitoutumatonOpetus should be(Some(true))
    val opiskeluoikeus = henkilo.opiskeluoikeudet.head
    opiskeluoikeus.suoritukset.head.vahvistus should be(None)
    KoskiUtil.deadlineDate = LocalDate.now().minusDays(1)

    val result = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo).head

    peruskouluB2KieletShouldNotBeValinnainen(result)

    result should have length 4
    getPerusopetusPäättötodistus(result).get.luokka shouldEqual "9C"
    result(2).suoritus.tila should equal("KESKEYTYNYT")
  }

  it should "parse arvosanat from peruskoulu_9_luokka_päättötodistus.json" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "peruskoulu_9_luokka_päättötodistus.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    val result = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo).head
    result should have length 4
    val pt = getPerusopetusPäättötodistus(result).get
    pt.luokka shouldEqual "9C"
    pt.suoritus.valmistuminen shouldEqual LocalDate.parse("2016-06-04")

    peruskouluB2KieletShouldNotBeValinnainen(result)
  }

  it should "not parse arvosanat from peruskoulu_9_luokka_päättötodistus_vahvistus_4_6_2018_jälkeen.json" in {
    val json: String = scala.io.Source
      .fromFile(jsonDir + "peruskoulu_9_luokka_päättötodistus_vahvistus_4_6_2018_jälkeen.json")
      .mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    val result = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo).head
    result should have length 4
    getPerusopetusPäättötodistus(result).get.luokka shouldEqual "9C"
    result(3).arvosanat should have length 18
  }

  it should "throw RuntimeException if henkiloOid is missing" in {
    /*
    Heitetään poikkeus, jos henkiloOid puuttuu.
     */

    val json: String =
      scala.io.Source.fromFile(jsonDir + "lukio_päättötodistus_puuttuva_henkilöOid.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    try {
      Await.result(
        koskiDatahandler.processHenkilonTiedotKoskesta(
          henkilo,
          PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
          new KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = true)
        ),
        5.seconds
      )
    } catch {
      case ex: RuntimeException => // Expected
    }

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
    val result = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo).head
    result should have length 1

    val suoritusArvosanat = result.head
    val arvosanat = suoritusArvosanat.arvosanat
    /*
    val expectedAineet: Set[String] = Set("AI", "A1", "B1", "B3", "MA", "BI", "GE", "FY", "KE", "KT", "FI", "PS", "HI", "YH", "LI", "MU", "KU", "TE", "ITT", "TO", "OA")
     */
    val expectedAineet: Set[String] = Set(
      "AI",
      "A1",
      "B1",
      "B3",
      "MA",
      "BI",
      "GE",
      "FY",
      "KE",
      "KT",
      "FI",
      "PS",
      "HI",
      "YH",
      "LI",
      "MU",
      "KU",
      "TE"
    )
    val aineet: Set[String] = arvosanat.map(a => a.aine).toSet

    aineet.toSeq.sorted shouldEqual expectedAineet.toSeq.sorted

    suoritusArvosanat.suoritus.tila shouldEqual "VALMIS"
    arvosanat.forall(_.valinnainen == false) shouldEqual true
  }

  it should "parse arvosanat besides S from lukio_päättötodistus2.json when switch to enable lukio import is enabled" in {

    val json: String = scala.io.Source.fromFile(jsonDir + "lukio_päättötodistus2.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    val result = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo).head
    result should have length 1

    val suoritusArvosanat = result.head
    val arvosanat = suoritusArvosanat.arvosanat

    val expectedAineet: Set[String] = Set(
      "A1",
      "AI",
      "B1",
      "BI",
      "FY",
      "GE",
      "HI",
      "KE",
      "KT",
      "KU",
      "LI",
      "MA",
      "MU",
      "PS",
      "TE",
      "YH"
    )
    val aineet: Set[String] = arvosanat.map(a => a.aine).toSet

    aineet.toSeq.sorted shouldEqual expectedAineet.toSeq.sorted
    suoritusArvosanat.suoritus.tila shouldEqual "VALMIS"

    val arvosanatuple = arvosanat.map(a => (a.aine, a.valinnainen)).toSet
    val expectedAineetTuple: Set[(String, Boolean)] = Set(
      "AI",
      "A1",
      "B1",
      "MA",
      "FY",
      "KE",
      "BI",
      "GE",
      "PS",
      "KT",
      "HI",
      "YH",
      "MU",
      "KU",
      "TE",
      "LI"
    ).map(s => (s, false))
    arvosanatuple shouldEqual expectedAineetTuple

  }

  //todo varmista, että tämä testi on rakennettu oikein ja mittaa oikeaa asiaa. Hajosi kun haluttiin vain myöhäisempi kahdesta saman tason opiskeluoikeudesta.
  /*  it should "parse BUG-1711.json" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "BUG-1711.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    val resultGroup = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo)
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
    val resultGroup = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo)
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
    val resultGroup = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo)
    resultGroup should have length 0
    //resultGroup.head should have length 0
  }

  it should "test data jäänyt_luokalle_peruskoulu.json" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "jäänyt_luokalle_peruskoulu.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    val resultGroup = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo)
    resultGroup should have length 1
    resultGroup.head should have length 3
    getPerusopetusPäättötodistus(resultGroup.head).get.luokka shouldEqual "9C"
  }

  it should "parse 1.2.246.562.24.40546864498.json" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "1.2.246.562.24.40546864498.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    val resultGroup = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo)
    resultGroup should have length 1
    resultGroup.head should have length 2

    val arvosanat = resultGroup.head
    getPerusopetusPäättötodistus(arvosanat).get.luokka shouldEqual "9H"
    arvosanat should have length 2
    val numKo = henkilo.opiskeluoikeudet.head.suoritukset.head.osasuoritukset
      .count(_.koulutusmoduuli.tunniste.get.koodiarvo.contentEquals("KO"))
    arvosanat.head.arvosanat.filter(
      _.aine.contentEquals("KO")
    ) should have length numKo - 1 //one KO has only 1 vuosiviikkotunti, it's not accepted
  }

  it should "parse VALMA_korotettava_historia.json" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "VALMA_korotettava_historia.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    val resultGroup = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo)
    resultGroup should have length 2
    resultGroup.last should have length 1
    val suoritusarvosanat = resultGroup.last
    suoritusarvosanat should have length 1
    val suoritusarvosana = suoritusarvosanat.head
    suoritusarvosana.arvosanat.exists(_.aine == "HI") shouldBe true
    val virallinensuoritus = suoritusarvosana.suoritus
    virallinensuoritus.komo shouldEqual Oids.perusopetuksenOppiaineenOppimaaraOid

    val luokkaAste = Some(9)

    val foo = virallinensuoritus.komo.equals(Oids.perusopetusKomoOid)
    val bar = suoritusarvosanat.exists(_.luokkataso.getOrElse("").startsWith("9")) || luokkaAste
      .getOrElse("")
      .equals(KoskiUtil.AIKUISTENPERUS_LUOKKAASTE)
    val peruskoulututkintoJaYsisuoritusTaiPKAikuiskoulutus = foo && bar

    if (
      virallinensuoritus.komo.equals(
        "luokka"
      ) || !(peruskoulututkintoJaYsisuoritusTaiPKAikuiskoulutus || !virallinensuoritus.komo.equals(
        Oids.perusopetusKomoOid
      ))
    ) {
      fail("should not be here")
    }
  }

  it should "combine multiple perusopetuksen oppiaineen oppimäärä data that share the same organisation" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "perusopetuksenOppimaaraCombine.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    //aine appended by grade
    val myöntäjäOrgAineet =
      List("AI7", "AI5", "MA5", "KT5", "HI7", "YH6", "FY5", "KE5", "BI6", "GE5")
    val myöntäjäOrg2Aineet = List("TEH")

    val resultGroup = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo)
    resultGroup should have length 1
    val result = resultGroup.head
    val resultByOrg = result.groupBy(_.suoritus.myontaja)

    resultByOrg.keys.toSeq.sorted shouldEqual Seq("1.2.246.562.10.11111111")

    resultByOrg("1.2.246.562.10.11111111").filter(
      _.suoritus.komo.contentEquals(Oids.perusopetuksenOppiaineenOppimaaraOid)
    ) should have length 1

    resultByOrg("1.2.246.562.10.11111111").head.arvosanat
      .map(a =>
        a.aine.concat(a.arvio match {
          case Arvio410(arvosana)         => arvosana
          case ArvioYo(arvosana, pisteet) => arvosana
          case ArvioOsakoe(arvosana)      => arvosana
          case ArvioHyvaksytty(arvosana)  => if (arvosana.contentEquals("hylatty")) "H" else arvosana
        })
      )
      .sorted shouldEqual myöntäjäOrgAineet.sorted

    resultByOrg("1.2.246.562.10.11111111").head.suoritus.valmistuminen shouldEqual LocalDate.parse(
      "2018-02-19"
    )
    resultByOrg(
      "1.2.246.562.10.11111111"
    ).head.suoritus.myontaja shouldEqual "1.2.246.562.10.11111111"
  }

  it should "parse testi_satu_valinnaiset.json" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "testi_satu_valinnaiset.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    val s = henkilo.opiskeluoikeudet.head.suoritukset.flatMap(_.osasuoritukset)

    val sourcematikat = henkilo.opiskeluoikeudet
      .flatMap(p => p.suoritukset)
      .flatMap(_.osasuoritukset)
      .filter(f => f.koulutusmoduuli.tunniste.get.koodiarvo == "MA")

    val sourcekässät = henkilo.opiskeluoikeudet
      .flatMap(p => p.suoritukset)
      .flatMap(_.osasuoritukset)
      .filter(f => f.koulutusmoduuli.tunniste.get.koodiarvo == "KS")

    sourcematikat.exists(_.koulutusmoduuli.pakollinen.contains(true))
    sourcematikat.exists(_.koulutusmoduuli.pakollinen.contains(false))

    sourcekässät.exists(_.koulutusmoduuli.pakollinen.contains(true))
    sourcekässät.exists(_.koulutusmoduuli.pakollinen.contains(false))

    val resultGroup = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo)
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

  //TODO Tässä testitapauksessa lähdesuorituksen pitäisi varmaan olla valmis&vahvistettu
  it should "parse testi3_satu_valinnaisuus.json" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "testi3_satu_valinnaisuus.json").mkString
    var henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    val s = henkilo.opiskeluoikeudet.head.suoritukset.flatMap(_.osasuoritukset)

    val sourceliikunnat = henkilo.opiskeluoikeudet
      .flatMap(p => p.suoritukset)
      .flatMap(_.osasuoritukset)
      .filter(f => f.koulutusmoduuli.tunniste.get.koodiarvo == "LI")

    sourceliikunnat should have length 2

    val res = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo)

    res should have length 1
    val suoritusarvosanat: SuoritusArvosanat = res.head.head
    val arvosanat = suoritusarvosanat.arvosanat.filter(_.aine == "LI")
    arvosanat should have length 2
    arvosanat.exists(_.valinnainen == true) shouldBe true
    arvosanat.exists(_.valinnainen == false) shouldBe true
  }

  it should "parse perusopetus_valinnaisuus_conflu_7.json" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "perusopetus_valinnaisuus_conflu_7.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    val res = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo)

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

    val res = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo)
    res should have length 1
    val arvosanat = res.head
    arvosanat should have length 3

    val expectedDate = LocalDate.parse("2018-05-07", DateTimeFormat.forPattern("yyyy-MM-dd"))
    arvosanat.head.suoritus.valmistuminen shouldBe expectedDate

  }

  it should "parse kymppiluokkatesti_kesken_conflu_2.json" in {
    /*
    Hetu 140601A511L (kymppiluokkalainen). Lisäsin koskeen sekä viime vuonna suoritetun perusopetuksen,
    että tänä vuonna suoritettavan 10-luokan ja muutaman arvosanan korotuksen. → 10-luokka arvosanoineen siirtyi,
    mutta suressa suorituksen tilana näkyy 'Keskeytynyt', vaikka Koskessa on läsnä.
    Pitäisi olla suressakin keskeneräisenä.(Minna Turunen 8.5.)
     */
    val json: String =
      scala.io.Source.fromFile(jsonDir + "kymppiluokkatesti_kesken_conflu_2.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    KoskiUtil.deadlineDate = LocalDate.now().plusDays(1)

    val seq = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo)
    seq should have length 2

    val res = seq(1)

    // OK-227 : Switch test back to original test since valmistumispäivä is in the past.
    //res should have length 0

    val suor: SuoritusArvosanat = res.head
    suor.suoritus should not be null
    val virallinen = suor.suoritus
    virallinen.komo shouldEqual Oids.lisaopetusKomoOid
    virallinen.tila shouldEqual "KESKEN"
  }

  it should "parse luokkatietoinen-testi.json" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "luokkatietoinen-testi.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    val resgroup = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo)
    resgroup should have length 1

    val res = resgroup.head
    res should have length 3

    res.exists(_.luokka.contentEquals("9A")) shouldEqual true
    res.exists(_.luokka.contentEquals("9D")) shouldEqual true

    val luokkatieto = res.filter(_.luokka.contentEquals("9D")).head
    luokkatieto.suoritus.tila shouldEqual "KESKEN"

    val päättötodistus = res.filter(_.suoritus.komo.contentEquals(Oids.perusopetusKomoOid)).head

    res.foreach {
      case SuoritusArvosanat(
            useSuoritus: VirallinenSuoritus,
            arvosanat: Seq[Arvosana],
            luokka: String,
            lasnaDate: LocalDate,
            luokkaTaso: Option[String]
          ) =>
        val peruskoulututkintoJaYsisuoritusTaiPKAikuiskoulutus = useSuoritus.komo.equals(
          Oids.perusopetusKomoOid
        ) && (res.exists(_.luokkataso.getOrElse("").startsWith("9"))
          || luokkaTaso.getOrElse("").equals(KoskiUtil.AIKUISTENPERUS_LUOKKAASTE))
        if (
          !useSuoritus.komo.equals(
            "luokka"
          ) && (peruskoulututkintoJaYsisuoritusTaiPKAikuiskoulutus || !useSuoritus.komo.equals(
            Oids.perusopetusKomoOid
          ))
        ) {
          peruskoulututkintoJaYsisuoritusTaiPKAikuiskoulutus shouldBe true
          useSuoritus.tila shouldEqual "KESKEN"
        } else {
          val ismatch = luokka.contentEquals("9A") || luokka.contentEquals("9D")
          ismatch shouldBe true
        }

      case _ => ???
    }

    //päättötodistus.luokka shouldBe empty
    //päättötodistus.suoritus.tila shouldEqual "KESKEN"
    //päättötodistus.suoritus.vahvistettu shouldBe false

  }

  it should "parse luokallejääjä_testi.json" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "luokallejääjä_testi.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    val resgroup = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo)
    resgroup should have length 1
    val pt = getPerusopetusPäättötodistus(resgroup.head).get
    val ysiluokat = getYsiluokat(resgroup.head)
    ysiluokat should have length 1
    ysiluokat.head.suoritus.valmistuminen.toString("dd.MM.YYYY") shouldEqual KoskiUtil.deadlineDate
      .toString("dd.MM.YYYY")
    pt.suoritus.valmistuminen.toString("dd.MM.YYYY") shouldEqual "01.06.2017"

  }

  it should "get correct end date from last ysiluokka" in {
    val koskikomo = KoskiKoulutusmoduuli(None, None, None, None, None)

    val vahvistus = KoskiVahvistus("2000-04-01", KoskiOrganisaatio(Some("")))
    val vahvistus2 = KoskiVahvistus("2000-05-03", KoskiOrganisaatio(Some("")))
    val vahvistus3 = KoskiVahvistus("2000-05-02", KoskiOrganisaatio(Some("")))

    val ks1 = KoskiSuoritus(
      luokka = Some("9"),
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
      jääLuokalle = None
    )

    val ks2 = ks1.copy(vahvistus = Some(vahvistus2))
    val ks3 = ks1.copy(vahvistus = Some(vahvistus3))

    val suoritukset: Seq[KoskiSuoritus] = Seq(ks1, ks2, ks3)
    val maybedate: Option[LocalDate] = koskiSuoritusParser.getEndDateFromLastNinthGrade(suoritukset)

    maybedate.get shouldEqual parseLocalDate("2000-05-03")
  }

  it should "get correct end date from last ysiluokka with empty vahvistus date or vahvistus is none" in {
    val koskikomo = KoskiKoulutusmoduuli(None, None, None, None, None)

    val vahvistus = KoskiVahvistus("2000-04-01", KoskiOrganisaatio(Some("")))
    val vahvistus2 = KoskiVahvistus("2000-05-03", KoskiOrganisaatio(Some("")))
    val vahvistus3 = KoskiVahvistus("2000-05-02", KoskiOrganisaatio(Some("")))
    val vahvistus4 = KoskiVahvistus("", KoskiOrganisaatio(Some("")))

    val ks1 = KoskiSuoritus(
      luokka = Some("9"),
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
      jääLuokalle = None
    )

    val ks2 = KoskiSuoritus(
      luokka = Some("9"),
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
      jääLuokalle = None
    )

    val ks3 = KoskiSuoritus(
      luokka = Some("9"),
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
      jääLuokalle = None
    )

    val ks4 = KoskiSuoritus(
      luokka = Some("9"),
      koulutusmoduuli = koskikomo,
      tyyppi = None,
      kieli = None,
      pakollinen = None,
      toimipiste = None,
      vahvistus = Some(vahvistus4),
      suorituskieli = None,
      arviointi = None,
      yksilöllistettyOppimäärä = None,
      osasuoritukset = Seq(),
      ryhmä = None,
      alkamispäivä = None,
      jääLuokalle = None
    )

    val ks5 = KoskiSuoritus(
      luokka = Some("9"),
      koulutusmoduuli = koskikomo,
      tyyppi = None,
      kieli = None,
      pakollinen = None,
      toimipiste = None,
      vahvistus = None,
      suorituskieli = None,
      arviointi = None,
      yksilöllistettyOppimäärä = None,
      osasuoritukset = Seq(),
      ryhmä = None,
      alkamispäivä = None,
      jääLuokalle = None
    )

    val suoritukset: Seq[KoskiSuoritus] = Seq(ks1, ks2, ks3, ks4, ks5)
    val maybedate: Option[LocalDate] = koskiSuoritusParser.getEndDateFromLastNinthGrade(suoritukset)

    maybedate.get shouldEqual parseLocalDate("2000-05-03")
  }

  it should "parse 1.2.246.562.24.14978931242.json" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "1.2.246.562.24.14978931242.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]

    henkilo.opiskeluoikeudet.head.aikaleima shouldEqual Some("2018-05-15T11:59:16.690066")
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    val resultgroup = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo)
    resultgroup should have length 1
    val result: Seq[SuoritusArvosanat] = resultgroup.head
    result should have length 2
    val arvosanat = result.head

    val expectedDate = LocalDate.parse("2018-05-15")
    arvosanat.suoritus.valmistuminen shouldEqual expectedDate
  }

  it should "filter valinnaiset aineet from aikuisten_perusopetus_valinnaiset2.json" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "aikuisten_perusopetus_valinnaiset2.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]

    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    val resultgroup = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo)
    resultgroup should have length 1
    val result: Seq[SuoritusArvosanat] = resultgroup.head
    result should have length 1

    result.head.arvosanat.filter(_.aine.contentEquals("B2")).head.valinnainen shouldEqual false
    result.head.arvosanat.filter(_.aine.contentEquals("A2")).head.valinnainen shouldEqual false

    val valinnaisetAineet = result.head.arvosanat.filter(_.valinnainen == true).map(_.aine)
    valinnaisetAineet shouldNot contain("KU")
    valinnaisetAineet should contain("AI")
  }

  /*it should "parse vahvistamaton telma_testi_valmis.json as keskeytynyt after deadline date" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "telma_testi_valmis.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]

    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    KoskiUtil.deadlineDate = LocalDate.now().minusDays(1)

    val resultgrp = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo)
    resultgrp should have length 1
    val result = resultgrp.head
    result should have length 1
    result.head.arvosanat should have length 0
    result.head.suoritus.tila shouldEqual "KESKEYTYNYT"
  }*/

  /*it should "parse vahvistamaton telma_testi_valmis.json as kesken before deadline date" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "telma_testi_valmis.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]

    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    KoskiUtil.deadlineDate = LocalDate.now().plusDays(1)

    val resultgrp = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo)
    resultgrp should have length 1
    val result = resultgrp.head
    result should have length 1
    result.head.arvosanat should have length 0
    result.head.suoritus.tila shouldEqual "KESKEN"
  }*/

  /*it should "parse telma_testi_kesken.json as keskeytynyt after deadline date" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "telma_testi_kesken.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]

    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    KoskiUtil.deadlineDate = LocalDate.now().minusDays(1)

    val resultgrp = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo)
    resultgrp should have length 1
    val result = resultgrp.head
    result should have length 1
    result.head.arvosanat should have length 0
    result.head.suoritus.tila shouldEqual "KESKEYTYNYT"
  }*/

  /*it should "parse telma_testi_kesken.json as kesken before deadline date" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "telma_testi_kesken.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]

    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    KoskiUtil.deadlineDate = LocalDate.now().plusDays(1)

    val resultgrp = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo)
    resultgrp should have length 1
    val result = resultgrp.head
    result should have length 1
    result.head.arvosanat should have length 0
    result.head.suoritus.tila shouldEqual "KESKEN"
  }*/

  it should "parse kielivalinnaisuustest.json" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "kielivalinnaisuustest.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]

    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    val resgroup = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo)
    val res = resgroup.head

    val arvosanat = getPerusopetusPäättötodistus(res).get.arvosanat
    peruskouluB2KieletShouldNotBeValinnainen(res)
  }

  it should "parse lukio_et_kt.json" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "lukio_et_kt.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]

    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    val resgroup = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo)
    //val resgroup = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo, createLukioArvosanat = true)
    val res: Seq[SuoritusArvosanat] = resgroup.head

    val arvosanat: Seq[Arvosana] = res.head.arvosanat
    arvosanat.count(_.aine.contentEquals("KT")) shouldEqual 2
  }

  it should "parse perusopetus_valinnaisuus_B1_yhteinen_ja_valinnainen.json" in {
    val json: String = scala.io.Source
      .fromFile(jsonDir + "perusopetus_valinnaisuus_B1_yhteinen_ja_valinnainen.json")
      .mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]

    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    val resgroup: Seq[Seq[SuoritusArvosanat]] =
      koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo)
    val pt = getPerusopetusPäättötodistus(resgroup.head)
    pt.get.arvosanat.filter(_.aine.contentEquals("B1")) should have length 2
    pt.get.arvosanat.filter(a => a.aine.contentEquals("B1") && a.valinnainen) should have length 1
    pt.get.arvosanat.filter(a => a.aine.contentEquals("B1") && !a.valinnainen) should have length 1
  }

  it should "interpret A2 and B2 langs as pakollinen in lisäopetus" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "A2B2ValinnaisetPakollisina_lisäopetus.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]

    val res: Seq[Seq[SuoritusArvosanat]] =
      koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo)
    res should have length 2

    val perusopetus = res.head
    val lisäopetus = res(1)

    val aineet = Set("A2", "B2")
    val perusA2B2 =
      getPerusopetusPäättötodistus(perusopetus).get.arvosanat.filter(a => aineet.contains(a.aine))
    perusA2B2 should have length 0

    val lisäA2B2 = lisäopetus.flatMap(_.arvosanat.filter(a => aineet.contains(a.aine)))
    lisäA2B2 should have length 2
    lisäA2B2.map(_.valinnainen) shouldEqual Seq(false, false)
  }

  it should "not set osasuoritus as yksilöllistetty if not pakollinen" in {
    var json: String = scala.io.Source.fromFile(jsonDir + "yksilöllistäminen.json").mkString
    var henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    var osasuoritukset: Seq[KoskiOsasuoritus] =
      henkilo.opiskeluoikeudet.head.suoritukset.head.osasuoritukset

    /*
    Kaikki pakolliset eivät ole yksilöllistettyjä, ei-pakolliset ovat -> Ei
    1: pakollinen kyllä, yksilöllistetty ei
    2: pakollinen kyllä, yksilöllistetty ei
    3: pakollinen ei, yksilöllistetty kyllä
    4: pakollinen ei, yksilöllistetty kyllä
     */
    var result = koskiSuoritusParser.osasuoritusToArvosana(
      henkilo.henkilö.oid.get,
      "TEST",
      osasuoritukset,
      None,
      None,
      false,
      LocalDate.now()
    )
    result._2 should equal(yksilollistaminen.Ei)

    json = scala.io.Source.fromFile(jsonDir + "yksilöllistäminen1.json").mkString
    henkilo = parse(json).extract[KoskiHenkiloContainer]
    osasuoritukset = henkilo.opiskeluoikeudet.head.suoritukset.head.osasuoritukset

    /*
    Yksi pakollinen yksilöllistetty -> Osittain
    1: pakollinen kyllä, yksilöllistetty ei
    2: pakollinen kyllä, yksilöllistetty ei
    3: pakollinen kyllä, yksilöllistetty kyllä
    4: pakollinen ei, yksilöllistetty kyllä
     */
    result = koskiSuoritusParser.osasuoritusToArvosana(
      henkilo.henkilö.oid.get,
      "TEST",
      osasuoritukset,
      None,
      None,
      false,
      LocalDate.now()
    )
    result._2 should equal(yksilollistaminen.Osittain)

    json = scala.io.Source.fromFile(jsonDir + "yksilöllistäminen2.json").mkString
    henkilo = parse(json).extract[KoskiHenkiloContainer]
    osasuoritukset = henkilo.opiskeluoikeudet.head.suoritukset.head.osasuoritukset

    /*
    Yksi pakollinen ja yksilöllistetty -> Kokonaan
    1: pakollinen ei, yksilöllistetty ei
    2: pakollinen ei, yksilöllistetty ei
    3: pakollinen kyllä, yksilöllistetty kyllä
    4: pakollinen ei, yksilöllistetty ei
     */
    result = koskiSuoritusParser.osasuoritusToArvosana(
      henkilo.henkilö.oid.get,
      "TEST",
      osasuoritukset,
      None,
      None,
      false,
      LocalDate.now()
    )
    result._2 should equal(yksilollistaminen.Kokonaan)
  }

  it should "store suoritus & arvosana if no overlapping opiskeluoikeus found" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "viimeisin_opiskeluoikeus.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]

    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    val params: KoskiSuoritusHakuParams =
      new KoskiSuoritusHakuParams(saveLukio = false, saveAmmatillinen = true)
    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        params
      ),
      5.seconds
    )
    val opiskelijat = run(database.run(sql"select henkilo_oid from opiskelija".as[String]))
    opiskelijat.size should equal(1)
    opiskelijat.head should equal("1.2.246.562.24.32656706483")
    val opiskelija = opiskelijat.head
    val suoritukset = run(
      database.run(sql"select resource_id from suoritus where henkilo_oid = $opiskelija".as[String])
    )
    suoritukset should have length 1
    val suoritus = suoritukset.head
    val arvosanat =
      run(database.run(sql"select * from arvosana where suoritus = $suoritus".as[String]))
    arvosanat should have length 2
  }

  it should "resolve latest perusopetuksen lasnaoleva opiskeluoikeus" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "koskidata_1pk_1amm.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]

    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = false, saveAmmatillinen = true)
      ),
      5.seconds
    )
    var suoritukset = run(
      database.run(
        sql"select myontaja from suoritus where komo = '1.2.246.562.13.62959769647'".as[String]
      )
    )
    suoritukset.size should equal(1)
    val myontaja = suoritukset.head
    myontaja should equal("1.2.246.562.10.33327422946")
    suoritukset = run(
      database.run(
        sql"select resource_id from suoritus where komo = '1.2.246.562.13.62959769647'".as[String]
      )
    )
    val suoritus = suoritukset.head.toString
    val arvosanat =
      run(database.run(sql"select * from arvosana where suoritus = $suoritus".as[String]))
    arvosanat should have length 18
  }

  //fixme
  it should "delete opiskelija, suoritus and arvosanat not existing in koski anymore" in {
    var json: String = scala.io.Source.fromFile(jsonDir + "koskidata_1pk_1amm.json").mkString
    var henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    val henkiloOid: String = henkilo.henkilö.oid.get.toString

    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = false, saveAmmatillinen = true)
      ),
      5.seconds
    )

    println("1st processing done " + System.currentTimeMillis())

    //Fake that the suoritukses were actually saved yesterday
    fakeHenkilonSuorituksetSavedAt(henkiloOid)

    println("1st update done " + System.currentTimeMillis())

    val suoritukset1 = run(
      database.run(
        sql"select resource_id from suoritus where not deleted and current and henkilo_oid = $henkiloOid"
          .as[String]
      )
    )
    suoritukset1.size should equal(2)

    json = scala.io.Source.fromFile(jsonDir + "koskidata_1amm.json").mkString
    henkilo = parse(json).extract[KoskiHenkiloContainer]

    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = false, saveAmmatillinen = true)
      ),
      5.seconds
    )

    println("2nd processing done " + System.currentTimeMillis())

    //Fake that the suoritukses were actually saved yesterday
    fakeHenkilonSuorituksetSavedAt(henkiloOid)

    println("2nd update done " + System.currentTimeMillis())

    val opiskelijat2 = run(
      database.run(
        sql"select henkilo_oid from opiskelija where not deleted and current and henkilo_oid = $henkiloOid"
          .as[String]
      )
    )
    opiskelijat2.size should equal(0)
    val suoritukset2 = run(
      database.run(
        sql"select resource_id from suoritus where not deleted and current and henkilo_oid = $henkiloOid"
          .as[String]
      )
    )
    suoritukset2.size should equal(1)
    val suoritus2 = suoritukset2.head
    val arvosanat2 = run(
      database.run(
        sql"select * from arvosana where not deleted and current and suoritus in (select resource_id from suoritus where not deleted and current and henkilo_oid = $henkiloOid)"
          .as[String]
      )
    )
    arvosanat2 should have length 0
  }

  it should "not delete opiskelija, suoritus and arvosanat not existing in koski anymore" +
    "if the suoritus was only recently saved" in {
      var json: String = scala.io.Source.fromFile(jsonDir + "koskidata_1pk_1amm.json").mkString
      var henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
      val henkiloOid: String = henkilo.henkilö.oid.get.toString

      henkilo should not be null
      henkilo.opiskeluoikeudet.head.tyyppi should not be empty

      Await.result(
        koskiDatahandler.processHenkilonTiedotKoskesta(
          henkilo,
          PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
          new KoskiSuoritusHakuParams(saveLukio = false, saveAmmatillinen = true)
        ),
        5.seconds
      )
      val suoritukset1 = run(
        database.run(
          sql"select resource_id from suoritus where not deleted and current and henkilo_oid = $henkiloOid"
            .as[String]
        )
      )
      suoritukset1.size should equal(2)

      json = scala.io.Source.fromFile(jsonDir + "koskidata_1amm.json").mkString
      henkilo = parse(json).extract[KoskiHenkiloContainer]

      henkilo should not be null
      henkilo.opiskeluoikeudet.head.tyyppi should not be empty

      Await.result(
        koskiDatahandler.processHenkilonTiedotKoskesta(
          henkilo,
          PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
          new KoskiSuoritusHakuParams(saveLukio = false, saveAmmatillinen = true)
        ),
        5.seconds
      )

      val suoritukset2 = run(
        database.run(
          sql"select resource_id from suoritus where not deleted and current and henkilo_oid = $henkiloOid"
            .as[String]
        )
      )
      suoritukset2.size should equal(3)

    }

  it should "Not delete opiskelija, suoritus and arvosanat if source is not koski" in {
    var json: String = scala.io.Source.fromFile(jsonDir + "koskidata_1pk_1amm.json").mkString
    var henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    val henkiloOid: String = henkilo.henkilö.oid.get.toString

    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = false, saveAmmatillinen = true)
      ),
      5.seconds
    )

    //Fake that the suoritukses were actually saved yesterday
    fakeHenkilonSuorituksetSavedAt(henkiloOid)

    var opiskelijat1 = run(
      database.run(
        sql"select henkilo_oid from opiskelija where not deleted and current and henkilo_oid = $henkiloOid"
          .as[String]
      )
    )
    opiskelijat1.size should equal(1)
    var suoritukset1 = run(
      database.run(
        sql"select resource_id from suoritus where not deleted and current and henkilo_oid = $henkiloOid"
          .as[String]
      )
    )
    suoritukset1.size should equal(2)
    var arvosanat1 = run(
      database.run(
        sql"select * from arvosana where not deleted and current and suoritus in (select resource_id from suoritus where not deleted and current and henkilo_oid = $henkiloOid)"
          .as[String]
      )
    )
    arvosanat1 should have length 18

    //Päivitetään peruskoulusuoritus ei koskesta tulleeksi:
    run(
      database.run(
        sql"update opiskelija set source = '1.2.246.562.24.35939175712' where oppilaitos_oid = '1.2.246.562.10.33327422946'"
          .as[String]
      )
    )
    run(
      database.run(
        sql"update suoritus set source = '1.2.246.562.24.35939175712' where myontaja = '1.2.246.562.10.33327422946'"
          .as[String]
      )
    )
    run(
      database.run(
        sql"update arvosana set source = '1.2.246.562.24.35939175712' where source = 'koski'"
          .as[String]
      )
    )

    val opiskelijat2 = run(
      database.run(
        sql"select henkilo_oid from opiskelija where not deleted and current and source = '1.2.246.562.24.35939175712' and henkilo_oid = $henkiloOid"
          .as[String]
      )
    )
    opiskelijat2.size should equal(1)
    val suoritukset2 = run(
      database.run(
        sql"select resource_id from suoritus where not deleted and current and source = '1.2.246.562.24.35939175712' and henkilo_oid = $henkiloOid"
          .as[String]
      )
    )
    suoritukset2.size should equal(1)
    val arvosanat2 = run(
      database.run(
        sql"select * from arvosana where not deleted and current and suoritus in (select resource_id from suoritus where not deleted and current and source = '1.2.246.562.24.35939175712' and henkilo_oid = $henkiloOid)"
          .as[String]
      )
    )
    arvosanat2 should have length 18

    json = scala.io.Source.fromFile(jsonDir + "koskidata_lukio.json").mkString
    henkilo = parse(json).extract[KoskiHenkiloContainer]

    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = false)
      ),
      5.seconds
    )

    val opiskelijat3 = run(
      database.run(
        sql"select henkilo_oid from opiskelija where not deleted and current and henkilo_oid = $henkiloOid"
          .as[String]
      )
    )
    opiskelijat3.size should equal(2)
    val suoritukset3 = run(
      database.run(
        sql"select resource_id from suoritus where not deleted and current and henkilo_oid = $henkiloOid"
          .as[String]
      )
    )
    suoritukset3.size should equal(2)
    val arvosanat3 = run(
      database.run(
        sql"select * from arvosana where not deleted and current and suoritus in (select resource_id from suoritus where not deleted and current and source = '1.2.246.562.24.35939175712' and henkilo_oid = $henkiloOid)"
          .as[String]
      )
    )
    arvosanat3 should have length 18
  }

  it should "store only peruskoulusuoritus when KoskiSuoritusHakuParams.saveAmmatillinen is false" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "koskidata_1pk_1amm.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = false)
      ),
      5.seconds
    )
    val opiskelijat = run(database.run(sql"select henkilo_oid from opiskelija".as[String]))
    opiskelijat.size should equal(1)
    val opiskelija = opiskelijat.head
    val suoritukset =
      run(database.run(sql"select komo from suoritus where henkilo_oid = $opiskelija".as[String]))
    suoritukset.size should equal(1)
    suoritukset.head should equal("1.2.246.562.13.62959769647")
  }

  it should "store ammatillinen suoritus when KoskiSuoritusHakuParams.saveAmmatillinen is true" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "koskidata_1pk_1amm.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = false, saveAmmatillinen = true)
      ),
      5.seconds
    )
    val opiskelijat = run(database.run(sql"select henkilo_oid from opiskelija".as[String]))
    opiskelijat.size should equal(1)
    val opiskelija = opiskelijat.head
    val suoritukset =
      run(database.run(sql"select komo from suoritus where henkilo_oid = $opiskelija".as[String]))
    suoritukset.size should equal(2)
  }

  it should "store lukiosuoritus when KoskiSuoritusHakuParams.saveLukio & KoskiSuoritusHakuParams.saveAmmatillinen is true" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "koskidata_lukio.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = true)
      ),
      5.seconds
    )

    val opiskelijat = run(database.run(sql"select henkilo_oid from opiskelija".as[String]))
    opiskelijat.size should equal(1)
    val opiskelija = opiskelijat.head
    val suoritus = run(
      database.run(
        sql"select valmistuminen from suoritus where henkilo_oid = $opiskelija".as[String]
      )
    )
    suoritus.size should equal(1)
  }

  it should "not store lukiosuoritus when KoskiSuoritusHakuParams.saveLukio & KoskiSuoritusHakuParams.saveAmmatillinen is false" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "koskidata_lukio.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    val henkiloOid: String = henkilo.henkilö.oid.toString
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = false, saveAmmatillinen = false)
      ),
      5.seconds
    )

    val opiskelijat = run(database.run(sql"select henkilo_oid from opiskelija".as[String]))
    opiskelijat.size should equal(0)
    val suoritus = run(
      database.run(
        sql"select valmistuminen from suoritus where henkilo_oid = $henkiloOid".as[String]
      )
    )
    suoritus.size should equal(0)
  }

  it should "store peruskoulu as keskeytynyt without arvosanat if deadline date is yesterday and no vahvistus" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "koskidata_peruskoulu_kesken.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    KoskiUtil.deadlineDate = LocalDate.now().minusDays(1)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = false, saveAmmatillinen = false)
      ),
      5.seconds
    )

    val opiskelijat = run(database.run(sql"select henkilo_oid from opiskelija".as[String]))
    opiskelijat.size should equal(1)
    val suoritus = run(database.run(sql"select tila from suoritus".as[String]))
    suoritus.head should equal("KESKEYTYNYT")

    val arvosanat = run(
      database.run(sql"select * from arvosana where deleted = false and current = true".as[String])
    )
    arvosanat should have length 0
  }

  it should "store peruskoulu as kesken without arvosanat if deadline date is today" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "koskidata_peruskoulu_kesken.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    KoskiUtil.deadlineDate = LocalDate.now()

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = false, saveAmmatillinen = false)
      ),
      5.seconds
    )

    val opiskelijat = run(database.run(sql"select henkilo_oid from opiskelija".as[String]))
    opiskelijat.size should equal(1)
    val suoritus = run(database.run(sql"select tila from suoritus".as[String]))
    suoritus.head should equal("KESKEN")
    val arvosanat = run(
      database.run(sql"select * from arvosana where deleted = false and current = true".as[String])
    )
    arvosanat should have length 0
  }

  it should "store peruskoulu as kesken without arvosanat if deadline date is tomorrow" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "koskidata_peruskoulu_kesken.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    KoskiUtil.deadlineDate = LocalDate.now().plusDays(1)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = false, saveAmmatillinen = false)
      ),
      5.seconds
    )

    val opiskelijat = run(database.run(sql"select henkilo_oid from opiskelija".as[String]))
    opiskelijat.size should equal(1)
    val suoritus = run(database.run(sql"select tila from suoritus".as[String]))
    suoritus.head should equal("KESKEN")
    val arvosanat = run(
      database.run(sql"select * from arvosana where deleted = false and current = true".as[String])
    )
    arvosanat should have length 0
  }

  it should "store peruskoulu with nelosia as kesken without arvosanat if nelosia deadline date is tomorrow" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "koskidata_peruskoulu_kesken_nelonen.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    KoskiUtil.deadlineDate = LocalDate.now().plusDays(15)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = false, saveAmmatillinen = false)
      ),
      5.seconds
    )

    val opiskelijat = run(database.run(sql"select henkilo_oid from opiskelija".as[String]))
    opiskelijat.size should equal(1)
    val suoritus = run(database.run(sql"select tila from suoritus".as[String]))
    suoritus.head should equal("KESKEN")

    val arvosanat = run(
      database.run(sql"select * from arvosana where deleted = false and current = true".as[String])
    )
    arvosanat should have length 0
  }

  it should "store peruskoulu with nelosia as kesken without arvosanat if nelosia deadline date is today" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "koskidata_peruskoulu_kesken_nelonen.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    KoskiUtil.deadlineDate = LocalDate.now().plusDays(14)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = false, saveAmmatillinen = false)
      ),
      5.seconds
    )

    val opiskelijat = run(database.run(sql"select henkilo_oid from opiskelija".as[String]))
    opiskelijat.size should equal(1)
    val suoritus = run(database.run(sql"select tila from suoritus".as[String]))
    suoritus.head should equal("KESKEN")
    val arvosanat = run(
      database.run(sql"select * from arvosana where deleted = false and current = true".as[String])
    )
    arvosanat should have length 0
  }

  it should "store peruskoulu with nelosia as kesken with arvosanat if nelosia deadline date is yesterday" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "koskidata_peruskoulu_kesken_nelonen.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    KoskiUtil.deadlineDate = LocalDate.now().plusDays(13)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = false, saveAmmatillinen = false)
      ),
      5.seconds
    )

    val opiskelijat = run(database.run(sql"select henkilo_oid from opiskelija".as[String]))
    opiskelijat.size should equal(1)
    val suoritus = run(database.run(sql"select tila from suoritus".as[String]))
    suoritus.head should equal("KESKEN")
    val arvosanat = run(
      database.run(sql"select * from arvosana where deleted = false and current = true".as[String])
    )
    arvosanat should have length 17
  }

  it should "store kymppiluokka as keskeytynyt with arvosanat if deadline date is yesterday" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "koskidata_kymppiluokka.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    KoskiUtil.deadlineDate = LocalDate.now().minusDays(1)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = false, saveAmmatillinen = false)
      ),
      5.seconds
    )

    val opiskelijat = run(database.run(sql"select henkilo_oid from opiskelija".as[String]))
    opiskelijat.size should equal(2)
    val suoritukset = run(database.run(sql"select count(*) from suoritus".as[String]))
    suoritukset.head should equal("2")
    val suoritus = run(
      database.run(
        sql"select tila from suoritus where myontaja = '1.2.246.562.10.771064431110'".as[String]
      )
    )
    suoritus.head should equal("KESKEYTYNYT")
    val arvosanat = run(
      database.run(sql"select * from arvosana where deleted = false and current = true".as[String])
    )
    arvosanat should have length 6
  }

  it should "store kymppiluokka as kesken with arvosanat if deadline date is tomorrow" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "koskidata_kymppiluokka.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    KoskiUtil.deadlineDate = LocalDate.now().plusDays(1)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = false, saveAmmatillinen = false)
      ),
      5.seconds
    )

    val opiskelijat = run(database.run(sql"select henkilo_oid from opiskelija".as[String]))
    opiskelijat.size should equal(2)
    val suoritukset = run(database.run(sql"select count(*) from suoritus".as[String]))
    suoritukset.head should equal("2")
    val suoritus = run(
      database.run(
        sql"select tila from suoritus where myontaja = '1.2.246.562.10.771064431110'".as[String]
      )
    )
    suoritus.head should equal("KESKEN")
    val arvosanat = run(
      database.run(sql"select * from arvosana where deleted = false and current = true".as[String])
    )
    arvosanat should have length 6
  }

  it should "store kymppiluokka as keskeytynyt with arvosanat if deadline date is yesterday if there are some hylättys" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "koskidata_kymppiluokka_hylattys.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    KoskiUtil.deadlineDate = LocalDate.now().minusDays(1)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = false, saveAmmatillinen = false)
      ),
      5.seconds
    )

    val opiskelijat = run(database.run(sql"select henkilo_oid from opiskelija".as[String]))
    opiskelijat.size should equal(2)
    val suoritukset = run(database.run(sql"select count(*) from suoritus".as[String]))
    suoritukset.head should equal("2")
    val suoritus = run(
      database.run(
        sql"select tila from suoritus where myontaja = '1.2.246.562.10.771064431110'".as[String]
      )
    )
    suoritus.head should equal("KESKEYTYNYT")
    val arvosanat = run(
      database.run(sql"select * from arvosana where deleted = false and current = true".as[String])
    )
    arvosanat should have length 6
  }

  it should "store kymppiluokka as kesken with arvosanat if deadline date is tomorrow if there are some hylättys" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "koskidata_kymppiluokka_4.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    KoskiUtil.deadlineDate = LocalDate.now().plusDays(1)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = false, saveAmmatillinen = false)
      ),
      5.seconds
    )

    val opiskelijat = run(database.run(sql"select henkilo_oid from opiskelija".as[String]))
    opiskelijat.size should equal(2)
    val suoritukset = run(database.run(sql"select count(*) from suoritus".as[String]))
    suoritukset.head should equal("2")
    val suoritus = run(
      database.run(
        sql"select tila from suoritus where myontaja = '1.2.246.562.10.771064431110'".as[String]
      )
    )
    suoritus.head should equal("KESKEN")
    val arvosanat = run(
      database.run(sql"select * from arvosana where deleted = false and current = true".as[String])
    )
    arvosanat should have length 6
  }

  it should "store perusopetukseen sitomaton kymppiluokka as kesken with arvosanat if deadline date is tomorrow" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "koskidata_vuosiluokkiin_sitomaton_opetus.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    KoskiUtil.deadlineDate = LocalDate.now().plusDays(1)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = false, saveAmmatillinen = false)
      ),
      5.seconds
    )

    val opiskelijat = run(database.run(sql"select henkilo_oid from opiskelija".as[String]))
    opiskelijat.size should equal(2)
    val suoritukset = run(database.run(sql"select count(*) from suoritus".as[String]))
    suoritukset.head should equal("2")
    var suoritus = run(
      database.run(
        sql"select tila from suoritus where myontaja = '1.2.246.562.10.771064431110'".as[String]
      )
    )
    suoritus.head should equal("KESKEN")
    suoritus = run(
      database.run(
        sql"select lahde_arvot from suoritus where myontaja = '1.2.246.562.10.771064431110'"
          .as[String]
      )
    )
    parse(suoritus.head)
      .extract[Map[String, String]]
      .getOrElse("vuosiluokkiin sitomaton opetus", "false")
      .toBoolean should equal(true)
    val arvosanat = run(
      database.run(sql"select * from arvosana where deleted = false and current = true".as[String])
    )
    arvosanat should have length 6
  }

  it should "store perusopetukseen sitomaton kymppiluokka as keskeytynyt with arvosanat if deadline date is yesterday" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "koskidata_vuosiluokkiin_sitomaton_opetus.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    KoskiUtil.deadlineDate = LocalDate.now().minusDays(1)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = false, saveAmmatillinen = false)
      ),
      5.seconds
    )

    val opiskelijat = run(database.run(sql"select henkilo_oid from opiskelija".as[String]))
    opiskelijat.size should equal(2)
    val suoritukset = run(database.run(sql"select count(*) from suoritus".as[String]))
    suoritukset.head should equal("2")
    var suoritus = run(
      database.run(
        sql"select tila from suoritus where myontaja = '1.2.246.562.10.771064431110'".as[String]
      )
    )
    suoritus.head should equal("KESKEYTYNYT")
    suoritus = run(
      database.run(
        sql"select lahde_arvot from suoritus where myontaja = '1.2.246.562.10.771064431110'"
          .as[String]
      )
    )

    parse(suoritus.head)
      .extract[Map[String, String]]
      .getOrElse("vuosiluokkiin sitomaton opetus", "false")
      .toBoolean should equal(true)
    val arvosanat = run(
      database.run(sql"select * from arvosana where deleted = false and current = true".as[String])
    )
    arvosanat should have length 6
  }

  it should "store valma as kesken without arvosanat if deadline date is tomorrow" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "koskidata_valma_kesken.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    KoskiUtil.deadlineDate = LocalDate.now().plusDays(1)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = false, saveAmmatillinen = false)
      ),
      5.seconds
    )

    val opiskelijat = run(database.run(sql"select henkilo_oid from opiskelija".as[String]))
    opiskelijat.size should equal(2)
    val suoritukset = run(database.run(sql"select count(*) from suoritus".as[String]))
    suoritukset.head should equal("2")
    val suoritus = run(database.run(sql"select tila from suoritus where komo = 'valma'".as[String]))
    suoritus.head should equal("KESKEN")
    val arvosanat = run(
      database.run(sql"select * from arvosana where deleted = false and current = true".as[String])
    )
    arvosanat should have length 0
  }

  it should "store valma as keskeytynyt without arvosanat if deadline date is yesterday" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "koskidata_valma_kesken.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    KoskiUtil.deadlineDate = LocalDate.now().minusDays(1)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = false, saveAmmatillinen = false)
      ),
      5.seconds
    )

    val opiskelijat = run(database.run(sql"select henkilo_oid from opiskelija".as[String]))
    opiskelijat.size should equal(2)
    val suoritukset = run(database.run(sql"select count(*) from suoritus".as[String]))
    suoritukset.head should equal("2")
    val suoritus = run(database.run(sql"select tila from suoritus where komo = 'valma'".as[String]))
    suoritus.head should equal("KESKEYTYNYT")
    val arvosanat = run(
      database.run(sql"select * from arvosana where deleted = false and current = true".as[String])
    )
    arvosanat should have length 0
  }

  it should "store valma as valmis without arvosanat if deadline date is tomorrow and has enough opintopistees" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "koskidata_valma_valmis.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    KoskiUtil.deadlineDate = LocalDate.now().plusDays(1)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = false, saveAmmatillinen = false)
      ),
      5.seconds
    )

    val opiskelijat = run(database.run(sql"select henkilo_oid from opiskelija".as[String]))
    opiskelijat.size should equal(2)
    val suoritukset = run(database.run(sql"select count(*) from suoritus".as[String]))
    suoritukset.head should equal("2")
    val suoritus = run(database.run(sql"select tila from suoritus where komo = 'valma'".as[String]))
    suoritus.head should equal("VALMIS")
    val arvosanat = run(
      database.run(sql"select * from arvosana where deleted = false and current = true".as[String])
    )
    arvosanat should have length 0
  }

  /*it should "store telma as kesken without arvosanat if deadline date is tomorrow and not enough opintopistees" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "telma_testi_kesken.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    KoskiUtil.deadlineDate = LocalDate.now().plusDays(1)

    Await.result(koskiDatahandler.processHenkilonTiedotKoskesta(henkilo,PersonOidsWithAliases(henkilo.henkilö.oid.toSet), new KoskiSuoritusHakuParams(saveLukio = false, saveAmmatillinen = false)), 5.seconds)

    val opiskelijat = run(database.run(sql"select henkilo_oid from opiskelija".as[String]))
    opiskelijat.size should equal(1)
    val suoritukset = run(database.run(sql"select count(*) from suoritus".as[String]))
    suoritukset.head should equal ("1")
    val suoritus = run(database.run(sql"select tila from suoritus where komo = 'telma'".as[String]))
    suoritus.head should equal("KESKEN")
    val arvosanat = run(database.run(sql"select * from arvosana where deleted = false and current = true".as[String]))
    arvosanat should have length 0
  }*/

  /*it should "store telma as keskeytynyt without arvosanat if deadline date is yesterday" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "telma_testi_kesken.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    KoskiUtil.deadlineDate = LocalDate.now().minusDays(1)

    Await.result(koskiDatahandler.processHenkilonTiedotKoskesta(henkilo,PersonOidsWithAliases(henkilo.henkilö.oid.toSet), new KoskiSuoritusHakuParams(saveLukio = false, saveAmmatillinen = false)), 5.seconds)

    val opiskelijat = run(database.run(sql"select henkilo_oid from opiskelija".as[String]))
    opiskelijat.size should equal(1)
    val suoritukset = run(database.run(sql"select count(*) from suoritus".as[String]))
    suoritukset.head should equal ("1")
    val suoritus = run(database.run(sql"select tila from suoritus where komo = 'telma'".as[String]))
    suoritus.head should equal("KESKEYTYNYT")
    val arvosanat = run(database.run(sql"select * from arvosana where deleted = false and current = true".as[String]))
    arvosanat should have length 0
  }*/

  it should "store vuosiluokkiin sitomaton as kesken without arvosanat if deadline date is tomorrow" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "koskidata_sitomaton.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    KoskiUtil.deadlineDate = LocalDate.now().plusDays(1)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = false, saveAmmatillinen = false)
      ),
      5.seconds
    )

    val opiskelijat = run(database.run(sql"select henkilo_oid from opiskelija".as[String]))
    opiskelijat.size should equal(1)
    val suoritukset = run(database.run(sql"select count(*) from suoritus".as[String]))
    suoritukset.head should equal("1")
    var suoritus = run(
      database.run(
        sql"select tila from suoritus where komo = '1.2.246.562.13.62959769647'".as[String]
      )
    )
    suoritus.head should equal("KESKEN")
    suoritus = run(
      database.run(
        sql"select lahde_arvot from suoritus where komo = '1.2.246.562.13.62959769647'".as[String]
      )
    )
    parse(suoritus.head)
      .extract[Map[String, String]]
      .getOrElse("vuosiluokkiin sitomaton opetus", "false")
      .toBoolean should equal(true)
    val arvosanat = run(
      database.run(sql"select * from arvosana where deleted = false and current = true".as[String])
    )
    arvosanat should have length 0
  }

  it should "store vuosiluokkiin sitomaton as keskeytynyt without arvosanat if deadline date is yesterday" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "koskidata_sitomaton.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    KoskiUtil.deadlineDate = LocalDate.now().minusDays(1)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = false, saveAmmatillinen = false)
      ),
      5.seconds
    )

    val opiskelijat = run(database.run(sql"select henkilo_oid from opiskelija".as[String]))
    opiskelijat.size should equal(1)
    val suoritukset = run(database.run(sql"select count(*) from suoritus".as[String]))
    suoritukset.head should equal("1")
    var suoritus = run(
      database.run(
        sql"select tila from suoritus where komo = '1.2.246.562.13.62959769647'".as[String]
      )
    )
    suoritus.head should equal("KESKEYTYNYT")
    suoritus = run(
      database.run(
        sql"select lahde_arvot from suoritus where komo = '1.2.246.562.13.62959769647'".as[String]
      )
    )
    parse(suoritus.head)
      .extract[Map[String, String]]
      .getOrElse("vuosiluokkiin sitomaton opetus", "false")
      .toBoolean should equal(true)
    val arvosanat = run(
      database.run(sql"select * from arvosana where deleted = false and current = true".as[String])
    )
    arvosanat should have length 3
  }

  it should "store vahvistettu and valmistunut lukiosuoritus with arvosanat before deadline date" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "koskidata_lukio_valmis_vahvistettu.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    KoskiUtil.deadlineDate = LocalDate.now().plusDays(1)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = false)
      ),
      5.seconds
    )

    val opiskelijat = run(database.run(sql"select henkilo_oid from opiskelija".as[String]))
    opiskelijat.size should equal(1)
    val suoritukset = run(database.run(sql"select count(*) from suoritus".as[String]))
    suoritukset.head should equal("1")
    var suoritus = run(
      database.run(sql"select tila from suoritus where komo = 'TODO lukio komo oid'".as[String])
    )
    suoritus.head should equal("VALMIS")
    suoritus = run(
      database.run(
        sql"select vahvistettu from suoritus where komo = 'TODO lukio komo oid'".as[String]
      )
    )
    suoritus.head should equal("t")
    val arvosanat = run(
      database.run(sql"select * from arvosana where deleted = false and current = true".as[String])
    )
    arvosanat should have length 3
  }

  it should "store vahvistettu and valmistunut lukiosuoritus with arvosanat after deadline date" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "koskidata_lukio_valmis_vahvistettu.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    KoskiUtil.deadlineDate = LocalDate.now().minusDays(1)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = false)
      ),
      5.seconds
    )

    val opiskelijat = run(database.run(sql"select henkilo_oid from opiskelija".as[String]))
    opiskelijat.size should equal(1)
    val suoritukset = run(database.run(sql"select count(*) from suoritus".as[String]))
    suoritukset.head should equal("1")
    var suoritus = run(
      database.run(sql"select tila from suoritus where komo = 'TODO lukio komo oid'".as[String])
    )
    suoritus.head should equal("VALMIS")
    suoritus = run(
      database.run(
        sql"select vahvistettu from suoritus where komo = 'TODO lukio komo oid'".as[String]
      )
    )
    suoritus.head should equal("t")
    val arvosanat = run(
      database.run(sql"select * from arvosana where deleted = false and current = true".as[String])
    )
    arvosanat should have length 3
  }

  it should "not store kesken oleva lukiosuoritus at all before deadline date" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "koskidata_lukio_kesken.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    KoskiUtil.deadlineDate = LocalDate.now().plusDays(1)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = false)
      ),
      5.seconds
    )

    val opiskelijat = run(database.run(sql"select henkilo_oid from opiskelija".as[String]))
    opiskelijat.size should equal(0)
    val suoritukset = run(database.run(sql"select count(*) from suoritus".as[String]))
    suoritukset.head should equal("0")
    val arvosanat = run(database.run(sql"select count(*) from arvosana".as[String]))
    arvosanat.head should equal("0")
  }

  it should "not store kesken oleva lukiosuoritus at all after deadline date" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "koskidata_lukio_kesken.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    KoskiUtil.deadlineDate = LocalDate.now().minusDays(1)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = false)
      ),
      5.seconds
    )

    val opiskelijat = run(database.run(sql"select henkilo_oid from opiskelija".as[String]))
    opiskelijat.size should equal(0)
    val suoritukset = run(database.run(sql"select count(*) from suoritus".as[String]))
    suoritukset.head should equal("0")
    val arvosanat = run(database.run(sql"select count(*) from arvosana".as[String]))
    arvosanat.head should equal("0")
  }

  it should "not store vahvistettu läsnäoleva lukiosuoritus at all before deadline date" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "koskidata_lukio_valmis_lasna.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    KoskiUtil.deadlineDate = LocalDate.now().plusDays(1)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = false)
      ),
      5.seconds
    )

    val opiskelijat = run(database.run(sql"select henkilo_oid from opiskelija".as[String]))
    opiskelijat.size should equal(0)
    val suoritukset = run(database.run(sql"select count(*) from suoritus".as[String]))
    suoritukset.head should equal("0")
    val arvosanat = run(database.run(sql"select count(*) from arvosana".as[String]))
    arvosanat.head should equal("0")
  }

  it should "not store vahvistettu läsnäoleva lukiosuoritus at all after deadline date" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "koskidata_lukio_valmis_lasna.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    KoskiUtil.deadlineDate = LocalDate.now().minusDays(1)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = false)
      ),
      5.seconds
    )

    val opiskelijat = run(database.run(sql"select henkilo_oid from opiskelija".as[String]))
    opiskelijat.size should equal(0)
    val suoritukset = run(database.run(sql"select count(*) from suoritus".as[String]))
    suoritukset.head should equal("0")
    val arvosanat = run(database.run(sql"select count(*) from arvosana".as[String]))
    arvosanat.head should equal("0")
  }

  it should "not store vahvistettu keskeytynyt lukiosuoritus at all before deadline date" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "koskidata_lukio_keskeytynyt_vahvistettu.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    KoskiUtil.deadlineDate = LocalDate.now().plusDays(1)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = false)
      ),
      5.seconds
    )

    val opiskelijat = run(database.run(sql"select henkilo_oid from opiskelija".as[String]))
    opiskelijat.size should equal(0)
    val suoritukset = run(database.run(sql"select count(*) from suoritus".as[String]))
    suoritukset.head should equal("0")
    val arvosanat = run(database.run(sql"select count(*) from arvosana".as[String]))
    arvosanat.head should equal("0")
  }

  it should "not store vahvistettu keskeytynyt lukiosuoritus at all after deadline date" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "koskidata_lukio_keskeytynyt_vahvistettu.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    KoskiUtil.deadlineDate = LocalDate.now().minusDays(1)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = false)
      ),
      5.seconds
    )

    val opiskelijat = run(database.run(sql"select henkilo_oid from opiskelija".as[String]))
    opiskelijat.size should equal(0)
    val suoritukset = run(database.run(sql"select count(*) from suoritus".as[String]))
    suoritukset.head should equal("0")
    val arvosanat = run(database.run(sql"select count(*) from arvosana".as[String]))
    arvosanat.head should equal("0")
  }

  it should "store only kesken oleva VALMA-suoritus as kesken and skip katsotaaneronneeksi-koulutus before deadline date" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "koskidata_valma_keskeytynyt_vuonna_2018.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    KoskiUtil.deadlineDate = LocalDate.now().plusDays(1)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = false)
      ),
      5.seconds
    )

    val suoritukset = run(
      database.run(
        sql"select tila from suoritus where komo = 'valma' and myontaja = '1.2.246.562.10.58998320111'"
          .as[String]
      )
    )
    suoritukset.size should equal(1)
    suoritukset.head should equal("KESKEN")
  }

  it should "store only kesken oleva VALMA-suoritus as keskeytynyt and skip katsotaaneronneeksi-koulutus after deadline date" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "koskidata_valma_keskeytynyt_vuonna_2018.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    KoskiUtil.deadlineDate = LocalDate.now().minusDays(1)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = false)
      ),
      5.seconds
    )

    val suoritukset = run(
      database.run(
        sql"select tila from suoritus where komo = 'valma' and myontaja = '1.2.246.562.10.58998320111'"
          .as[String]
      )
    )
    suoritukset.size should equal(1)
    suoritukset.head should equal("KESKEYTYNYT")
  }

  it should "store suoritus without arvosanat as keskeytynyt if marked as jää luokalle in Koski 30 days before deadline date" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "koskidata_ysiluokka_jaa_luokalle.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    KoskiUtil.deadlineDate = LocalDate.now().plusDays(30)

    val arvosanat1 = run(database.run(sql"select count(*) from arvosana".as[String]))
    arvosanat1.head should equal("0")

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = true)
      ),
      5.seconds
    )

    val suoritukset = run(database.run(sql"select tila from suoritus".as[String]))
    suoritukset.size should equal(1)
    suoritukset.head should equal("KESKEYTYNYT")
    val arvosanat = run(database.run(sql"select count(*) from arvosana".as[String]))
    arvosanat.head should equal("0")
  }

  it should "store suoritus without arvosanat as keskeytynyt if marked as jää luokalle in Koski 10 days before deadline date" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "koskidata_ysiluokka_jaa_luokalle.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    KoskiUtil.deadlineDate = LocalDate.now().minusDays(10)

    val arvosanat1 = run(database.run(sql"select count(*) from arvosana".as[String]))
    arvosanat1.head should equal("0")

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = true)
      ),
      5.seconds
    )

    val suoritukset = run(database.run(sql"select tila from suoritus".as[String]))
    suoritukset.size should equal(1)
    suoritukset.head should equal("KESKEYTYNYT")
    val arvosanat = run(database.run(sql"select count(*) from arvosana".as[String]))
    arvosanat.head should equal("0")
  }

  it should "correct valmistumispäivämäärä in keskeytynyt peruskoulusuoritus with hylätty" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "koskidata_pk_eronnut_valmistumispaivamaara.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    KoskiUtil.deadlineDate = LocalDate.now().plusDays(30)

    //insert old opiskelijadata:
    var insertSql =
      sql"""insert into opiskelija (resource_id, oppilaitos_oid, luokkataso, luokka, henkilo_oid, alku_paiva, loppu_paiva, inserted, deleted, source, current)
       values ('44976e21-89b5-47b9-9b0e-ad834127691d', '1.2.246.562.10.15514292604', '9', '9A', '1.2.246.562.24.80710434876', '1533675600000', '1553205600000', '1553251575074', 'false', 'koski', 'true')"""
    run(database.run(insertSql.as[String]))
    insertSql =
      sql"""insert into opiskelija (resource_id, oppilaitos_oid, luokkataso, luokka, henkilo_oid, alku_paiva, loppu_paiva, inserted, deleted, source, current)
      values ('44976e21-89b5-47b9-9b0e-ad834127691d', '1.2.246.562.10.15514292604', '9', '9A', '1.2.246.562.24.80710434876', '1533675600000', '1551218400000', '1551263645506', 'false', 'koski', 'false')"""
    run(database.run(insertSql.as[String]))
    insertSql =
      sql"""insert into opiskelija (resource_id, oppilaitos_oid, luokkataso, luokka, henkilo_oid, alku_paiva, loppu_paiva, inserted, deleted, source, current)
      values ('44976e21-89b5-47b9-9b0e-ad834127691d', '1.2.246.562.10.15514292604', '9', '9A', '1.2.246.562.24.80710434876', '1502312400000', '1527109200000', '1527170002421', 'false', 'koski', 'false')"""
    run(database.run(insertSql.as[String]))
    insertSql =
      sql"""insert into opiskelija (resource_id, oppilaitos_oid, luokkataso, luokka, henkilo_oid, alku_paiva, loppu_paiva, inserted, deleted, source, current)
      values ('3ba39685-d0d1-4408-824d-7b210f47747a', '1.2.246.562.10.15673993224', '9', 'AIK 9', '1.2.246.562.24.80710434876', '1546812000000', '1559509200000', '1550876635684', 'false', 'koski', 'true')"""
    run(database.run(insertSql.as[String]))
    insertSql =
      sql"""insert into opiskelija (resource_id, oppilaitos_oid, luokkataso, luokka, henkilo_oid, alku_paiva, loppu_paiva, inserted, deleted, source, current)
      values ('44976e21-89b5-47b9-9b0e-ad834127691d', '1.2.246.562.10.15514292604', '9', '9A', '1.2.246.562.24.80710434876', '1502312400000', '1528059600000', '1527124364701', 'false', 'koski', 'false')"""
    run(database.run(insertSql.as[String]))
    insertSql =
      sql"""insert into opiskelija (resource_id, oppilaitos_oid, luokkataso, luokka, henkilo_oid, alku_paiva, loppu_paiva, inserted, deleted, source, current)
      values ('44976e21-89b5-47b9-9b0e-ad834127691d', '1.2.246.562.10.15514292604', '9', '9A', '1.2.246.562.24.80710434876', '1516572000000', '1528059600000', '1520530200047', 'false', 'koski', 'false')"""
    run(database.run(insertSql.as[String]))
    insertSql =
      sql"""insert into opiskelija (resource_id, oppilaitos_oid, luokkataso, luokka, henkilo_oid, alku_paiva, loppu_paiva, inserted, deleted, source, current)
      values ('44976e21-89b5-47b9-9b0e-ad834127691d', '1.2.246.562.10.15514292604', '9', '9A', '1.2.246.562.24.80710434876', '1502312400000', '1528059600000', '1518804700014', 'false', 'koski', 'false')"""
    run(database.run(insertSql.as[String]))
    //insert old suoritusdata:
    insertSql =
      sql"""insert into suoritus (resource_id, komo, myontaja, tila, valmistuminen, henkilo_oid, yksilollistaminen, suoritus_kieli, inserted, deleted, source, vahvistettu, current, lahde_arvot)
      values ('89f3c04f-0275-4d80-a3fe-eb03bb29f585', '1.2.246.562.13.62959769647', '1.2.246.562.10.15514292604', 'KESKEYTYNYT', '2019-03-22', '1.2.246.562.24.80710434876', 'Ei', 'FI', '1553251575068', 'false', '1.2.246.562.10.00000000001', 'true', 'true', '{}')"""
    run(database.run(insertSql.as[String]))
    insertSql =
      sql"""insert into suoritus (resource_id, komo, myontaja, tila, valmistuminen, henkilo_oid, yksilollistaminen, suoritus_kieli, inserted, deleted, source, vahvistettu, current, lahde_arvot)
      values ('89f3c04f-0275-4d80-a3fe-eb03bb29f585', '1.2.246.562.13.62959769647', '1.2.246.562.10.15514292604', 'KESKEYTYNYT', '2019-02-27', '1.2.246.562.24.80710434876', 'Ei', 'FI', '1551263645491', 'false', '1.2.246.562.10.00000000001', 'true', 'false', '{}')"""
    run(database.run(insertSql.as[String]))
    insertSql =
      sql"""insert into suoritus (resource_id, komo, myontaja, tila, valmistuminen, henkilo_oid, yksilollistaminen, suoritus_kieli, inserted, deleted, source, vahvistettu, current, lahde_arvot)
      values ('89f3c04f-0275-4d80-a3fe-eb03bb29f585', '1.2.246.562.13.62959769647', '1.2.246.562.10.15514292604', 'KESKEYTYNYT', '2018-05-24', '1.2.246.562.24.80710434876', 'Ei', 'FI', '1527170002708', 'false', '1.2.246.562.10.00000000001', 'true', 'false', '{}')"""
    run(database.run(insertSql.as[String]))
    insertSql =
      sql"""insert into suoritus (resource_id, komo, myontaja, tila, valmistuminen, henkilo_oid, yksilollistaminen, suoritus_kieli, inserted, deleted, source, vahvistettu, current, lahde_arvot)
      values ('47e3121c-8cc3-40b6-995f-c1bee0b70c11', '1.2.246.562.13.62959769647', '1.2.246.562.10.15673993224', 'KESKEN', '2019-06-03', '1.2.246.562.24.80710434876', 'Ei', 'FI', '1550876635674', 'false', 'koski', 'true', 'true', '{}')"""
    run(database.run(insertSql.as[String]))
    insertSql =
      sql"""insert into suoritus (resource_id, komo, myontaja, tila, valmistuminen, henkilo_oid, yksilollistaminen, suoritus_kieli, inserted, deleted, source, vahvistettu, current, lahde_arvot)
      values ('89f3c04f-0275-4d80-a3fe-eb03bb29f585', '1.2.246.562.13.62959769647', '1.2.246.562.10.15514292604', 'KESKEN', '2018-06-04', '1.2.246.562.24.80710434876', 'Ei', 'FI', '1518804700011', 'false', '1.2.246.562.10.00000000001', 'true', 'false', '{}')"""
    run(database.run(insertSql.as[String]))

    var valmistumispäivä = run(
      database.run(
        sql"select valmistuminen from suoritus where resource_id = '89f3c04f-0275-4d80-a3fe-eb03bb29f585' and current = 'true'"
          .as[String]
      )
    )
    valmistumispäivä.head should equal("2019-03-22")

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = false)
      ),
      5.seconds
    )

    valmistumispäivä = run(
      database.run(
        sql"select valmistuminen from suoritus where resource_id = '89f3c04f-0275-4d80-a3fe-eb03bb29f585' and current = 'true'"
          .as[String]
      )
    )
    valmistumispäivä.head should equal("2018-03-22")
  }

  it should "continue suoritus saving even if there is faulty one" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "koskidata_aloitus_deadlinepvm_jalkeen.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    KoskiUtil.deadlineDate = new LocalDate("2019-06-03")

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = true)
      ),
      5.seconds
    )

    val opiskelija = run(database.run(sql"select count(*) from opiskelija".as[String]))
    opiskelija.head should equal("1")
    val suoritukset = run(database.run(sql"select count(*) from suoritus".as[String]))
    suoritukset.head should equal("1")
  }

  it should "store alle 30 opintopisteen valma-suoritus as KESKEN before deadline date" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "koskidata_valma_valmis_alle_30_op.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    KoskiUtil.deadlineDate = LocalDate.now().plusDays(30)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = true)
      ),
      5.seconds
    )

    var opiskelija = run(database.run(sql"select count(*) from opiskelija".as[String]))
    opiskelija.head should equal("1")
    var suoritukset = run(database.run(sql"select count(*) from suoritus".as[String]))
    suoritukset.head should equal("1")
    var suoritusTilat = run(database.run(sql"select tila from suoritus".as[String]))
    suoritusTilat.head should equal("KESKEN")
  }

  it should "store alle 30 opintopisteen valma-suoritus as KESKEYTYNYT after deadline date" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "koskidata_valma_valmis_alle_30_op.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    KoskiUtil.deadlineDate = LocalDate.now().minusDays(30)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = true)
      ),
      5.seconds
    )

    var opiskelija = run(database.run(sql"select count(*) from opiskelija".as[String]))
    opiskelija.head should equal("1")
    var suoritukset = run(database.run(sql"select count(*) from suoritus".as[String]))
    suoritukset.head should equal("1")
    var suoritusTilat = run(database.run(sql"select tila from suoritus".as[String]))
    suoritusTilat.head should equal("KESKEYTYNYT")
  }

  it should "set correct luokkatieto when detecting oppilaitos and luokka" in {
    //LUVA
    var suoritusLuokka = SuoritusLuokka(
      VirallinenSuoritus(
        "1.2.246.562.5.2013112814572429142840",
        "1.2.246.562.10.96398657237",
        "KESKEN",
        new LocalDate("2019-05-02"),
        "1.2.246.562.24.60460151267",
        yksilollistaminen.Ei,
        "FI",
        None,
        true,
        "koski",
        None
      ),
      "LUVA",
      new LocalDate("2018-08-27"),
      None
    )
    var opiskelija =
      koskiOpiskelijaParser.createOpiskelija("1.2.246.562.24.80710434876", suoritusLuokka)

    opiskelija.get.luokkataso should equal("ML")
    opiskelija.get.oppilaitosOid should equal("1.2.246.562.10.96398657237")
    opiskelija.get.luokka should equal("LUVA")

    //Lukio
    suoritusLuokka = SuoritusLuokka(
      VirallinenSuoritus(
        "TODO lukio komo oid",
        "1.2.246.562.10.96398657237",
        "KESKEN",
        new LocalDate("2019-05-02"),
        "1.2.246.562.24.60460151267",
        yksilollistaminen.Ei,
        "FI",
        None,
        true,
        "koski",
        None
      ),
      "11A",
      new LocalDate("2018-08-27"),
      None
    )
    opiskelija =
      koskiOpiskelijaParser.createOpiskelija("1.2.246.562.24.80710434876", suoritusLuokka)

    opiskelija.get.luokkataso should equal("L")
    opiskelija.get.oppilaitosOid should equal("1.2.246.562.10.96398657237")
    opiskelija.get.luokka should equal("11A")

    //Ammatillinen
    suoritusLuokka = SuoritusLuokka(
      VirallinenSuoritus(
        "TODO ammatillinen komo oid",
        "1.2.246.562.10.96398657237",
        "KESKEN",
        new LocalDate("2019-05-02"),
        "1.2.246.562.24.60460151267",
        yksilollistaminen.Ei,
        "FI",
        None,
        true,
        "koski",
        None
      ),
      "AMM",
      new LocalDate("2018-08-27"),
      None
    )
    opiskelija =
      koskiOpiskelijaParser.createOpiskelija("1.2.246.562.24.80710434876", suoritusLuokka)

    opiskelija should be(None)

    //Ammatilliseen valmistava
    suoritusLuokka = SuoritusLuokka(
      VirallinenSuoritus(
        "1.2.246.562.5.2013112814572441001730",
        "1.2.246.562.10.96398657237",
        "KESKEN",
        new LocalDate("2019-05-02"),
        "1.2.246.562.24.60460151267",
        yksilollistaminen.Ei,
        "FI",
        None,
        true,
        "koski",
        None
      ),
      "MAVA13",
      new LocalDate("2018-08-27"),
      None
    )
    opiskelija =
      koskiOpiskelijaParser.createOpiskelija("1.2.246.562.24.80710434876", suoritusLuokka)

    opiskelija.get.luokkataso should equal("M")
    opiskelija.get.oppilaitosOid should equal("1.2.246.562.10.96398657237")
    opiskelija.get.luokka should equal("MAVA13")

    //Ammattistartti
    suoritusLuokka = SuoritusLuokka(
      VirallinenSuoritus(
        "1.2.246.562.5.2013112814572438136372",
        "1.2.246.562.10.96398657237",
        "KESKEN",
        new LocalDate("2019-05-02"),
        "1.2.246.562.24.60460151267",
        yksilollistaminen.Ei,
        "FI",
        None,
        true,
        "koski",
        None
      ),
      "OHVA",
      new LocalDate("2018-08-27"),
      None
    )
    opiskelija =
      koskiOpiskelijaParser.createOpiskelija("1.2.246.562.24.80710434876", suoritusLuokka)

    opiskelija.get.luokkataso should equal("A")
    opiskelija.get.oppilaitosOid should equal("1.2.246.562.10.96398657237")
    opiskelija.get.luokka should equal("OHVA")

    //Valmentava
    suoritusLuokka = SuoritusLuokka(
      VirallinenSuoritus(
        "1.2.246.562.5.2013112814572435755085",
        "1.2.246.562.10.96398657237",
        "KESKEN",
        new LocalDate("2019-05-02"),
        "1.2.246.562.24.60460151267",
        yksilollistaminen.Ei,
        "FI",
        None,
        true,
        "koski",
        None
      ),
      "VALO",
      new LocalDate("2018-08-27"),
      None
    )
    opiskelija =
      koskiOpiskelijaParser.createOpiskelija("1.2.246.562.24.80710434876", suoritusLuokka)

    opiskelija.get.luokkataso should equal("V")
    opiskelija.get.oppilaitosOid should equal("1.2.246.562.10.96398657237")
    opiskelija.get.luokka should equal("VALO")

    //VALMA
    suoritusLuokka = SuoritusLuokka(
      VirallinenSuoritus(
        "valma",
        "1.2.246.562.10.96398657237",
        "KESKEN",
        new LocalDate("2019-05-02"),
        "1.2.246.562.24.60460151267",
        yksilollistaminen.Ei,
        "FI",
        None,
        true,
        "koski",
        None
      ),
      "VALMA15",
      new LocalDate("2018-08-27"),
      None
    )
    opiskelija =
      koskiOpiskelijaParser.createOpiskelija("1.2.246.562.24.80710434876", suoritusLuokka)

    opiskelija.get.luokkataso should equal("VALMA")
    opiskelija.get.oppilaitosOid should equal("1.2.246.562.10.96398657237")
    opiskelija.get.luokka should equal("VALMA15")

    //TELMA
    suoritusLuokka = SuoritusLuokka(
      VirallinenSuoritus(
        "telma",
        "1.2.246.562.10.96398657237",
        "KESKEN",
        new LocalDate("2019-05-02"),
        "1.2.246.562.24.60460151267",
        yksilollistaminen.Ei,
        "FI",
        None,
        true,
        "koski",
        None
      ),
      "TELMA15",
      new LocalDate("2018-08-27"),
      None
    )
    opiskelija =
      koskiOpiskelijaParser.createOpiskelija("1.2.246.562.24.80710434876", suoritusLuokka)

    opiskelija.get.luokkataso should equal("TELMA")
    opiskelija.get.oppilaitosOid should equal("1.2.246.562.10.96398657237")
    opiskelija.get.luokka should equal("TELMA15")

    //Ammatillinen tutkinto
    suoritusLuokka = SuoritusLuokka(
      VirallinenSuoritus(
        "ammatillinentutkinto komo oid",
        "1.2.246.562.10.96398657237",
        "KESKEN",
        new LocalDate("2019-05-02"),
        "1.2.246.562.24.60460151267",
        yksilollistaminen.Ei,
        "FI",
        None,
        true,
        "koski",
        None
      ),
      "",
      new LocalDate("2018-08-27"),
      None
    )
    opiskelija =
      koskiOpiskelijaParser.createOpiskelija("1.2.246.562.24.80710434876", suoritusLuokka)

    opiskelija should be(None)

    //Peruskoulu luokkataso 9
    suoritusLuokka = SuoritusLuokka(
      VirallinenSuoritus(
        "1.2.246.562.13.62959769647",
        "1.2.246.562.10.96398657237",
        "KESKEN",
        new LocalDate("2019-05-02"),
        "1.2.246.562.24.60460151267",
        yksilollistaminen.Ei,
        "FI",
        None,
        true,
        "koski",
        None
      ),
      "9A",
      new LocalDate("2018-08-27"),
      Some("9")
    )
    opiskelija =
      koskiOpiskelijaParser.createOpiskelija("1.2.246.562.24.80710434876", suoritusLuokka)

    opiskelija.get.luokkataso should equal("9")
    opiskelija.get.oppilaitosOid should equal("1.2.246.562.10.96398657237")
    opiskelija.get.luokka should equal("9A")

    //Peruskoulu luokkataso AIK
    suoritusLuokka = SuoritusLuokka(
      VirallinenSuoritus(
        "1.2.246.562.13.62959769647",
        "1.2.246.562.10.96398657237",
        "KESKEN",
        new LocalDate("2019-05-02"),
        "1.2.246.562.24.60460151267",
        yksilollistaminen.Ei,
        "FI",
        None,
        true,
        "koski",
        None
      ),
      "AIK 9",
      new LocalDate("2018-08-27"),
      Some("AIK")
    )
    opiskelija =
      koskiOpiskelijaParser.createOpiskelija("1.2.246.562.24.80710434876", suoritusLuokka)

    opiskelija.get.luokkataso should equal("9")
    opiskelija.get.oppilaitosOid should equal("1.2.246.562.10.96398657237")
    opiskelija.get.luokka should equal("AIK 9")

    //Lisäopetus luokka 10
    suoritusLuokka = SuoritusLuokka(
      VirallinenSuoritus(
        "1.2.246.562.5.2013112814572435044876",
        "1.2.246.562.10.96398657237",
        "KESKEN",
        new LocalDate("2019-05-02"),
        "1.2.246.562.24.60460151267",
        yksilollistaminen.Ei,
        "FI",
        None,
        true,
        "koski",
        None
      ),
      "10A",
      new LocalDate("2018-08-27"),
      Some("10")
    )
    opiskelija =
      koskiOpiskelijaParser.createOpiskelija("1.2.246.562.24.80710434876", suoritusLuokka)

    opiskelija.get.luokkataso should equal("10")
    opiskelija.get.oppilaitosOid should equal("1.2.246.562.10.96398657237")
    opiskelija.get.luokka should equal("10A")

    //Lisäopetus luokka tyhjä
    suoritusLuokka = SuoritusLuokka(
      VirallinenSuoritus(
        "1.2.246.562.5.2013112814572435044876",
        "1.2.246.562.10.96398657237",
        "KESKEN",
        new LocalDate("2019-05-02"),
        "1.2.246.562.24.60460151267",
        yksilollistaminen.Ei,
        "FI",
        None,
        true,
        "koski",
        None
      ),
      "",
      new LocalDate("2018-08-27"),
      None
    )
    opiskelija =
      koskiOpiskelijaParser.createOpiskelija("1.2.246.562.24.80710434876", suoritusLuokka)

    opiskelija.get.luokkataso should equal("10")
    opiskelija.get.oppilaitosOid should equal("1.2.246.562.10.96398657237")
    opiskelija.get.luokka should equal("10")

    //Perusopetuksen oppiaineen oppimäärä
    suoritusLuokka = SuoritusLuokka(
      VirallinenSuoritus(
        "TODO perusopetuksenOppiaineenOppimäärä",
        "1.2.246.562.10.96398657237",
        "KESKEN",
        new LocalDate("2019-05-02"),
        "1.2.246.562.24.60460151267",
        yksilollistaminen.Ei,
        "FI",
        None,
        true,
        "koski",
        None
      ),
      "XX",
      new LocalDate("2018-08-27"),
      None
    )
    opiskelija =
      koskiOpiskelijaParser.createOpiskelija("1.2.246.562.24.80710434876", suoritusLuokka)

    opiskelija.get.luokkataso should equal("OPPIAINE")
    opiskelija.get.oppilaitosOid should equal("1.2.246.562.10.96398657237")
    opiskelija.get.luokka should equal("OPPIAINE")

    //Erikoisammattitutkinto
    suoritusLuokka = SuoritusLuokka(
      VirallinenSuoritus(
        "erikoisammattitutkinto komo oid",
        "1.2.246.562.10.96398657237",
        "KESKEN",
        new LocalDate("2019-05-02"),
        "1.2.246.562.24.60460151267",
        yksilollistaminen.Ei,
        "FI",
        None,
        true,
        "koski",
        None
      ),
      "XX",
      new LocalDate("2018-08-27"),
      None
    )
    opiskelija =
      koskiOpiskelijaParser.createOpiskelija("1.2.246.562.24.80710434876", suoritusLuokka)

    opiskelija should be(None)

    //Jokin muu komo
    suoritusLuokka = SuoritusLuokka(
      VirallinenSuoritus(
        "tuntematon komo oid",
        "1.2.246.562.10.96398657237",
        "KESKEN",
        new LocalDate("2019-05-02"),
        "1.2.246.562.24.60460151267",
        yksilollistaminen.Ei,
        "FI",
        None,
        true,
        "koski",
        None
      ),
      "XX",
      new LocalDate("2018-08-27"),
      None
    )
    opiskelija =
      koskiOpiskelijaParser.createOpiskelija("1.2.246.562.24.80710434876", suoritusLuokka)

    opiskelija should equal(None)

  }

  it should "filter suoritus without läsnäolo before or after deadline date" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "koskidata_valma_loma_ei_lasnaoloa.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    KoskiUtil.deadlineDate = LocalDate.now().plusDays(30)
    koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo) should be(Seq())

    KoskiUtil.deadlineDate = LocalDate.now().minusDays(30)
    koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo) should be(Seq())
  }

  it should "filter suoritus with future läsnäolo before or after deadline date" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "koskidata_valma_alku_01082019.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    KoskiUtil.deadlineDate = new LocalDate("2019-06-03").plusDays(30)
    koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo) should be(Seq())

    KoskiUtil.deadlineDate = new LocalDate("2019-06-03").minusDays(30)
    koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo) should be(Seq())
  }

  it should "store correct luokka and oppilaitosoid for erikoisammattitutkinto" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "koskidata_erikoisammattitutkinto.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    KoskiUtil.deadlineDate = LocalDate.now().plusDays(30)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = true)
      ),
      5.seconds
    )

    var opiskelija = run(database.run(sql"select count(*) from opiskelija".as[String]))
    opiskelija.head should equal("0")

    var suoritukset = run(database.run(sql"select count(*) from suoritus".as[String]))
    suoritukset.head should equal("1")

    suoritukset = run(database.run(sql"select komo from suoritus".as[String]))
    suoritukset.head should equal("erikoisammattitutkinto komo oid")
  }
  it should "get first start date from opiskeluoikeus" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "koskidata_lasnaolopaattely.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    KoskiUtil.deadlineDate = new LocalDate("2019-06-03")

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = true)
      ),
      5.seconds
    )

    val opiskelija = run(
      database.run(
        sql"select TO_CHAR(TO_TIMESTAMP(alku_paiva / 1000), 'YYYY-MM-DD') from opiskelija"
          .as[String]
      )
    )
    opiskelija.head should equal("2018-08-12")
  }

  it should "not store opiskelija or suoritus with unknown komo" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "koskidata_tuntematon_komo.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    KoskiUtil.deadlineDate = LocalDate.now().plusDays(30)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = true)
      ),
      5.seconds
    )

    val opiskelija = run(database.run(sql"select count(*) from opiskelija".as[String]))
    opiskelija.head should equal("0")

    val suoritukset = run(database.run(sql"select count(*) from suoritus".as[String]))
    suoritukset.head should equal("0")

  }

  it should "only store arvosanat with numbers, not any alphabets" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "koskidata_ei_numeeriset_arvosanat.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    KoskiUtil.deadlineDate = LocalDate.now().plusDays(30)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = true)
      ),
      5.seconds
    )

    val opiskelija = run(database.run(sql"select count(*) from opiskelija".as[String]))
    opiskelija.head should equal("1")

    val suoritukset = run(database.run(sql"select count(*) from suoritus".as[String]))
    suoritukset.head should equal("1")

    var arvosanat =
      run(database.run(sql"select count(*) from arvosana where arvosana = 'S'".as[String]))
    arvosanat.head should equal("0")

    arvosanat = run(database.run(sql"select count(*) from arvosana".as[String]))
    arvosanat.head should equal("3")
  }

  it should "store perusopetuksen suoritus as valmis and also save arvosanas if after deadline and contains nelosia" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "perusopetus_with_nelosia_kesken.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    KoskiUtil.deadlineDate = LocalDate.now().minusDays(7)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = false)
      ),
      5.seconds
    )
    val suoritusTilat: Seq[String] = run(database.run(sql"select tila from suoritus".as[String]))
    suoritusTilat.head should equal("VALMIS")
    val arvosanat = run(database.run(sql"select count(*) from arvosana".as[String]))
    arvosanat.head should equal("13")

  }

  it should "store perusopetuksen suoritus as kesken but save arvosanas if under 2 weeks before deadline and contains nelosia" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "perusopetus_with_nelosia_kesken.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    KoskiUtil.deadlineDate = LocalDate.now().plusDays(7)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = false)
      ),
      5.seconds
    )
    val suoritusTilat: Seq[String] = run(database.run(sql"select tila from suoritus".as[String]))
    suoritusTilat.head should equal("KESKEN")
    val arvosanat = run(database.run(sql"select count(*) from arvosana".as[String]))
    arvosanat.head should equal("13")

  }

  it should "store valinnaiset äidinkielet with correct ordering" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "koskidata_valinnaisia_aidinkielia.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    KoskiUtil.deadlineDate = LocalDate.now().plusDays(7)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = true)
      ),
      5.seconds
    )

    val opiskelija = run(database.run(sql"select count(*) from opiskelija".as[String]))
    opiskelija.head should equal("1")

    val suoritukset = run(database.run(sql"select count(*) from suoritus".as[String]))
    suoritukset.head should equal("1")

    var arvosanat = run(
      database.run(
        sql"select count(*) from arvosana where aine = 'AI' and current = true".as[String]
      )
    )
    arvosanat.head should equal("8")

    arvosanat = run(
      database.run(
        sql"select count(*) from arvosana where aine = 'AI' and current = true and valinnainen = true and lisatieto = 'FI' and jarjestys is not null"
          .as[String]
      )
    )
    arvosanat.head should equal("4")

    arvosanat = run(
      database.run(
        sql"select count(*) from arvosana where aine = 'AI' and current = true and valinnainen = true and lisatieto = 'RI' and jarjestys is not null"
          .as[String]
      )
    )
    arvosanat.head should equal("3")

    arvosanat = run(
      database.run(
        sql"select count(*) from arvosana where aine = 'AI' and current = true and valinnainen = true and lisatieto = 'FI' and jarjestys = 1"
          .as[String]
      )
    )
    arvosanat.head should equal("1")
  }

  it should "store 2 separate opiskeluoikeutta and valmis tilaiset arvosanat when perusopetuksen oppiaineen oppimäärä and tila equals läsnä" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "koskidata_aik_perusopetus_poo.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    KoskiUtil.deadlineDate = LocalDate.now().plusDays(30)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = true)
      ),
      5.seconds
    )

    val opiskelija = run(database.run(sql"select count(*) from opiskelija".as[String]))
    opiskelija.head should equal("2")

    val suoritukset = run(database.run(sql"select count(*) from suoritus".as[String]))
    suoritukset.head should equal("2")

    var arvosanat =
      run(database.run(sql"select count(*) from arvosana where current = true".as[String]))
    arvosanat.head should equal("2")

    arvosanat =
      run(database.run(sql"select count(*) from arvosana where aine like 'BI%'".as[String]))
    arvosanat.head should equal("1")

    arvosanat =
      run(database.run(sql"select count(*) from arvosana where aine like 'HI%'".as[String]))
    arvosanat.head should equal("1")
  }

  it should "store latest aikuisten perusopetus with separate perusopetuksen oppiaineen oppimääräs" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "koskidata_aik_perusopetus_poo_useita.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    KoskiUtil.deadlineDate = LocalDate.now().plusDays(30)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = true)
      ),
      5.seconds
    )

    val opiskelija = run(database.run(sql"select count(*) from opiskelija".as[String]))
    opiskelija.head should equal("3")

    var opiskelijaTyyppi = run(
      database.run(
        sql"select count(*) from opiskelija where luokka = 'AIK 9' and current = true".as[String]
      )
    )
    opiskelijaTyyppi.head should equal("1")

    opiskelijaTyyppi = run(
      database.run(
        sql"select count(*) from opiskelija where luokka = 'OPPIAINE' and current = true".as[String]
      )
    )
    opiskelijaTyyppi.head should equal("2")

    var suoritukset = run(database.run(sql"select count(*) from suoritus".as[String]))
    suoritukset.head should equal("3")

    var suoritusTyyppi = run(
      database.run(
        sql"select count(*) from suoritus where komo = '1.2.246.562.13.62959769647' and current = true"
          .as[String]
      )
    )
    suoritusTyyppi.head should equal("1")

    suoritusTyyppi = run(
      database.run(
        sql"select count(*) from suoritus where komo = 'TODO perusopetuksenOppiaineenOppimäärä' and current = true"
          .as[String]
      )
    )
    suoritusTyyppi.head should equal("2")

    var arvosanat =
      run(database.run(sql"select count(*) from arvosana where current = true".as[String]))
    arvosanat.head should equal("20")

    suoritukset = run(
      database.run(
        sql"select resource_id from suoritus where komo = '1.2.246.562.13.62959769647'".as[String]
      )
    )
    var suoritus = suoritukset.head.toString

    arvosanat =
      run(database.run(sql"select count(*) from arvosana where suoritus = $suoritus".as[String]))
    arvosanat.head should equal("17")

    suoritukset = run(
      database.run(
        sql"select resource_id from suoritus where komo = 'TODO perusopetuksenOppiaineenOppimäärä' and myontaja = '1.2.246.562.10.32727448402'"
          .as[String]
      )
    )
    suoritus = suoritukset.head.toString

    arvosanat =
      run(database.run(sql"select count(*) from arvosana where suoritus = $suoritus".as[String]))
    arvosanat.head should equal("2")

    suoritukset = run(
      database.run(
        sql"select resource_id from suoritus where komo = 'TODO perusopetuksenOppiaineenOppimäärä' and myontaja = '1.2.246.562.10.81044480515'"
          .as[String]
      )
    )
    suoritus = suoritukset.head.toString

    arvosanat =
      run(database.run(sql"select count(*) from arvosana where suoritus = $suoritus".as[String]))
    arvosanat.head should equal("1")
  }

  it should "not create suoritus from vahvistamaton ammatillinen tutkinto suoritus" in {
    val henkilo = parse(
      scala.io.Source.fromFile(jsonDir + "koskidata_2amm_vahvistettu_vahvistamaton.json").mkString
    ).extract[KoskiHenkiloContainer]
    val suoritukset = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo)
    suoritukset.size should equal(1)
  }

  it should "create distinct suoritukset from two ammatillinen perustutkinto from same oppilaitos" in {
    val henkilo = parse(
      scala.io.Source.fromFile(jsonDir + "koskidata_2amm_sama_oppilaitos.json").mkString
    ).extract[KoskiHenkiloContainer]
    val suoritukset = koskiDatahandler.createSuorituksetJaArvosanatFromKoski(henkilo).flatten
    suoritukset.size should equal(2)
    val a = suoritukset.head
    val b = suoritukset.tail.head
    (a.suoritus.komo == b.suoritus.komo) should be(true)
    (a.suoritus.core == b.suoritus.core) should be(false)
  }

  it should "delete no arvosanas when nothing has changed" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "koskidata_arvosanat_version_1.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    KoskiUtil.deadlineDate = LocalDate.now().minusDays(7)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = false)
      ),
      5.seconds
    )
    val arvosanat = run(
      database.run(sql"select count(*) from arvosana where current and not deleted".as[String])
    ).head
    arvosanat should equal("18")

    //Run the same koskidata again; deleted arvosanas in db should not increase here.
    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = false)
      ),
      5.seconds
    )
    val deleted =
      run(database.run(sql"select count(*) from arvosana where deleted".as[String])).head
    val arvosanatAfter = run(
      database.run(sql"select count(*) from arvosana where current and not deleted".as[String])
    ).head
    deleted should equal("0")
    arvosanatAfter should equal("18")
  }

  trait KoskiDataArvosanatUpdateUtils {
    def verifyArvosanatVersion1() = {
      val arvosanat = run(
        database.run(sql"select count(*) from arvosana where current and not deleted".as[String])
      ).head
      val arvosana_TE = run(
        database.run(sql"select arvosana from arvosana where aine = 'TE' and current".as[String])
      ).head
      val arvosana_HI = run(
        database.run(sql"select arvosana from arvosana where aine = 'HI' and current".as[String])
      ).head
      val arvosana_YH = run(
        database.run(sql"select arvosana from arvosana where aine = 'YH' and current".as[String])
      ).head
      val suoritusTilat: Seq[String] = run(database.run(sql"select tila from suoritus".as[String]))
      suoritusTilat.head should equal("VALMIS")
      arvosanat should equal("18")
      arvosana_TE should equal("6")
      arvosana_HI should equal("8")
      arvosana_YH should equal("6")
    }

    def verifyArvosanatVersion1UpdatedWithVersion2() = {
      val opiskelijat = run(database.run(sql"select henkilo_oid from opiskelija".as[String]))
      opiskelijat.size should equal(1)
      val suoritusTilat = run(database.run(sql"select tila from suoritus where current".as[String]))
      suoritusTilat should have length 1
      suoritusTilat.head should equal("VALMIS")
      val arvosana_TE_after = run(
        database.run(sql"select arvosana from arvosana where aine = 'TE' and current".as[String])
      ).head
      val arvosana_HI_after = run(
        database.run(sql"select arvosana from arvosana where aine = 'HI' and current".as[String])
      ).head
      val arvosana_YH_after = run(
        database.run(sql"select arvosana from arvosana where aine = 'YH' and current".as[String])
      ).head
      val arvosanas_after = run(
        database.run(sql"select count(*) from arvosana where current and not deleted".as[String])
      ).head
      val arvosana_TE_noncurrent = run(
        database.run(
          sql"select arvosana from arvosana where aine = 'TE' and not current".as[String]
        )
      ).head
      val arvosana_HI_noncurrent = run(
        database.run(
          sql"select arvosana from arvosana where aine = 'HI' and not current".as[String]
        )
      ).head
      val arvosana_YH_noncurrent = run(
        database.run(
          sql"select arvosana from arvosana where aine = 'YH' and not current".as[String]
        )
      ).head
      val deleteds_after = run(
        database.run(sql"select count(*) from arvosana where deleted".as[String])
      ).head
      arvosana_TE_after should equal("8")
      arvosana_HI_after should equal("7")
      arvosana_YH_after should equal("10")
      arvosana_TE_noncurrent should equal("6")
      arvosana_HI_noncurrent should equal("8")
      arvosana_YH_noncurrent should equal("6")
      arvosanas_after should equal("17")
      deleteds_after should equal("1")
    }

    val henkilo = getHenkilo("koskidata_arvosanat_version_1.json")
    val henkilo2 = getHenkilo("koskidata_arvosanat_version_2.json")

    KoskiUtil.deadlineDate = LocalDate.now().minusDays(7)
  }

  it should "properly handle 3 changed and 1 removed arvosanas" in new KoskiDataArvosanatUpdateUtils {
    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = false)
      ),
      5.seconds
    )

    verifyArvosanatVersion1()

    //Now run actually changed data.
    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo2,
        PersonOidsWithAliases(henkilo2.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = false)
      ),
      5.seconds
    )

    verifyArvosanatVersion1UpdatedWithVersion2()
  }

  it should "properly update arvosanas when person is identified with alias" in new KoskiDataArvosanatUpdateUtils {
    val originalOid: String = henkilo.henkilö.oid.getOrElse("impossible")
    val alias = "1.2.3.4.5.6"
    val personOidsWithAliases =
      PersonOidsWithAliases(Set(originalOid), Map(originalOid -> Set(originalOid, alias)))

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        personOidsWithAliases,
        new KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = false)
      ),
      5.seconds
    )

    verifyArvosanatVersion1()

    val henkilo2identifiedByAlias =
      henkilo2.copy(henkilö = henkilo2.henkilö.copy(oid = Some(alias)))

    // This should update the same person
    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo2identifiedByAlias,
        personOidsWithAliases,
        new KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = false)
      ),
      5.seconds
    )

    verifyArvosanatVersion1UpdatedWithVersion2()
  }

  it should "properly update arvosanas when person was first identified by alias" in new KoskiDataArvosanatUpdateUtils {
    val originalOid: String = henkilo.henkilö.oid.getOrElse("impossible")
    val alias = "1.2.3.4.5.6"
    val personOidsWithAliasesInitial = PersonOidsWithAliases(Set(alias), Map(alias -> Set(alias)))

    val henkiloIdentifiedByAlias = henkilo.copy(henkilö = henkilo.henkilö.copy(oid = Some(alias)))

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkiloIdentifiedByAlias,
        personOidsWithAliasesInitial,
        new KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = false)
      ),
      5.seconds
    )

    verifyArvosanatVersion1()

    // This should update the same person, this time identified by "master oid"
    val personOidsWithAliasesNew =
      PersonOidsWithAliases(Set(originalOid), Map(originalOid -> Set(originalOid, alias)))

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo2,
        personOidsWithAliasesNew,
        new KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = false)
      ),
      5.seconds
    )

    verifyArvosanatVersion1UpdatedWithVersion2()
  }

  trait KoskiDataSuorituksetUpdateUtils {
    def verifySuorituksetCount(expectedSuorituksetCount: Int) = {
      val suoritukset = run(
        database.run(sql"select tila from suoritus where not deleted".as[String])
      )
      suoritukset should have length expectedSuorituksetCount
    }

    val henkilo = getHenkilo("koskidata_amm_ja_lukio.json")

    KoskiUtil.deadlineDate = LocalDate.now().minusDays(7)
  }

  it should "properly handle suoritukset when using different parameters" in new KoskiDataSuorituksetUpdateUtils {
    val originalOid: String = henkilo.henkilö.oid.getOrElse("impossible")
    val personOidsWithAliases =
      PersonOidsWithAliases(Set(originalOid), Map(originalOid -> Set(originalOid)))

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        personOidsWithAliases,
        KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = false)
      ),
      5.seconds
    )

    verifySuorituksetCount(1)

    // Same data with different parameters
    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        personOidsWithAliases,
        KoskiSuoritusHakuParams(saveLukio = false, saveAmmatillinen = true)
      ),
      5.seconds
    )

    verifySuorituksetCount(2)
  }

  it should "properly handle multiple valinnaises arvosanas for same ainees" in {
    val json: String = scala.io.Source
      .fromFile(jsonDir + "valinnaiset_4_kuvataidetta_3_musiikkia_before.json")
      .mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    val json2: String = scala.io.Source
      .fromFile(jsonDir + "valinnaiset_4_kuvataidetta_3_musiikkia_after.json")
      .mkString
    val henkilo2: KoskiHenkiloContainer = parse(json2).extract[KoskiHenkiloContainer]
    henkilo2 should not be null
    henkilo2.opiskeluoikeudet.head.tyyppi should not be empty

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = false)
      ),
      5.seconds
    )
    val kuvataitees = run(
      database.run(
        sql"select count(*) from arvosana where aine = 'KU' and valinnainen and current and not deleted"
          .as[String]
      )
    ).head
    val musiikkis = run(
      database.run(
        sql"select count(*) from arvosana where aine = 'MU' and valinnainen and current and not deleted"
          .as[String]
      )
    ).head
    kuvataitees should equal("4")
    musiikkis should equal("3")

    val ku_0_arvosana = run(
      database.run(
        sql"select arvosana from arvosana where aine = 'KU' and jarjestys = '0' and valinnainen and current and not deleted"
          .as[String]
      )
    ).head
    val ku_1_arvosana = run(
      database.run(
        sql"select arvosana from arvosana where aine = 'KU' and jarjestys = '1' and valinnainen and current and not deleted"
          .as[String]
      )
    ).head
    val ku_2_arvosana = run(
      database.run(
        sql"select arvosana from arvosana where aine = 'KU' and jarjestys = '2' and valinnainen and current and not deleted"
          .as[String]
      )
    ).head
    val ku_3_arvosana = run(
      database.run(
        sql"select arvosana from arvosana where aine = 'KU' and jarjestys = '3' and valinnainen and current and not deleted"
          .as[String]
      )
    ).head
    ku_0_arvosana should equal("9")
    ku_1_arvosana should equal("7")
    ku_2_arvosana should equal("10")
    ku_3_arvosana should equal("5")

    val mu_0_arvosana = run(
      database.run(
        sql"select arvosana from arvosana where aine = 'MU' and jarjestys = '0' and valinnainen and current and not deleted"
          .as[String]
      )
    ).head
    val mu_1_arvosana = run(
      database.run(
        sql"select arvosana from arvosana where aine = 'MU' and jarjestys = '1' and valinnainen and current and not deleted"
          .as[String]
      )
    ).head
    val mu_2_arvosana = run(
      database.run(
        sql"select arvosana from arvosana where aine = 'MU' and jarjestys = '2' and valinnainen and current and not deleted"
          .as[String]
      )
    ).head
    mu_0_arvosana should equal("7")
    mu_1_arvosana should equal("8")
    mu_2_arvosana should equal("9")

    val deleteds_before =
      run(database.run(sql"select count(*) from arvosana where deleted".as[String])).head
    deleteds_before should equal("0")

    //Ajetaan "Koskessa muuttunut" data
    //Poistettu MU-arvosana 8, poistettu KU-arvosana 9,
    //muutettu MU 9 -> 10, muutettu KU 10 -> 6
    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo2,
        PersonOidsWithAliases(henkilo2.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = false)
      ),
      5.seconds
    )
    val kuvataitees_after = run(
      database.run(
        sql"select count(*) from arvosana where aine = 'KU' and valinnainen and current and not deleted"
          .as[String]
      )
    ).head
    val musiikkis_after = run(
      database.run(
        sql"select count(*) from arvosana where aine = 'MU' and valinnainen and current and not deleted"
          .as[String]
      )
    ).head
    kuvataitees_after should equal("3")
    musiikkis_after should equal("2")

    val ku_0_arvosana_after = run(
      database.run(
        sql"select arvosana from arvosana where aine = 'KU' and jarjestys = '0' and valinnainen and current and not deleted"
          .as[String]
      )
    ).head
    val ku_1_arvosana_after = run(
      database.run(
        sql"select arvosana from arvosana where aine = 'KU' and jarjestys = '1' and valinnainen and current and not deleted"
          .as[String]
      )
    ).head
    val ku_2_arvosana_after = run(
      database.run(
        sql"select arvosana from arvosana where aine = 'KU' and jarjestys = '2' and valinnainen and current and not deleted"
          .as[String]
      )
    ).head
    ku_0_arvosana_after should equal("7")
    ku_1_arvosana_after should equal("6")
    ku_2_arvosana_after should equal("5")

    val mu_0_arvosana_after = run(
      database.run(
        sql"select arvosana from arvosana where aine = 'MU' and jarjestys = '0' and valinnainen and current and not deleted"
          .as[String]
      )
    ).head
    val mu_1_arvosana_after = run(
      database.run(
        sql"select arvosana from arvosana where aine = 'MU' and jarjestys = '1' and valinnainen and current and not deleted"
          .as[String]
      )
    ).head
    mu_0_arvosana_after should equal("7")
    mu_1_arvosana_after should equal("10")

    val deleteds_after =
      run(database.run(sql"select count(*) from arvosana where deleted".as[String])).head
    deleteds_after should equal("2")
  }

  it should "update suoritus lahdeArvot correctly before deadline date" in {
    var json: String =
      scala.io.Source.fromFile(jsonDir + "koskidata_suoritus_update_before.json").mkString
    var henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    KoskiUtil.deadlineDate = LocalDate.now().plusDays(1)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = false, saveAmmatillinen = false)
      ),
      5.seconds
    )

    var suoritus: Seq[String] = run(
      database.run(
        sql"select lahde_arvot from suoritus where henkilo_oid = '1.2.246.562.24.75034821549' and komo = '1.2.246.562.13.62959769647'"
          .as[String]
      )
    )

    parse(suoritus.head)
      .extract[Map[String, String]]
      .getOrElse("vuosiluokkiin sitomaton opetus", "true")
      .toBoolean should equal(false)

    json = scala.io.Source.fromFile(jsonDir + "koskidata_suoritus_update_after.json").mkString
    henkilo = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = false, saveAmmatillinen = false)
      ),
      5.seconds
    )

    suoritus = run(
      database.run(
        sql"select lahde_arvot from suoritus where henkilo_oid = '1.2.246.562.24.75034821549' and komo = '1.2.246.562.13.62959769647' and current = true"
          .as[String]
      )
    )
    parse(suoritus.head)
      .extract[Map[String, String]]
      .getOrElse("vuosiluokkiin sitomaton opetus", "false")
      .toBoolean should equal(true)
  }

  it should "store henkilon suoritukset even when there are doubled luokkas in koskidata" in {
    val json: String = scala.io.Source.fromFile(jsonDir + "pk_kaksi_kasiluokkaa.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    val originalOid: String = henkilo.henkilö.oid.getOrElse("impossible")
    val personOidsWithAliases =
      PersonOidsWithAliases(Set(originalOid), Map(originalOid -> Set(originalOid)))

    KoskiUtil.deadlineDate = LocalDate.now().plusDays(7)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        personOidsWithAliases,
        KoskiSuoritusHakuParams(saveLukio = false, saveAmmatillinen = false)
      ),
      5.seconds
    )
    val suoritusTilat: Seq[String] = run(database.run(sql"select tila from suoritus".as[String]))
    suoritusTilat.head should equal("VALMIS")
    suoritusTilat.size should equal(1)
    val arvosanat = run(database.run(sql"select count(*) from arvosana".as[String]))
    arvosanat.head should equal("18")
  }

  it should "parse peruskoulusuoritus with Yksilollistaminen.Alueittain from new data format" in {
    val json: String = scala.io.Source
      .fromFile(jsonDir + "yksilollistetty_toiminta_alueittain_uusi_muoto.json")
      .mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    val originalOid: String = henkilo.henkilö.oid.getOrElse("impossible")
    val personOidsWithAliases =
      PersonOidsWithAliases(Set(originalOid), Map(originalOid -> Set(originalOid)))

    KoskiUtil.deadlineDate = LocalDate.now().plusDays(7)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        personOidsWithAliases,
        KoskiSuoritusHakuParams(saveLukio = false, saveAmmatillinen = false)
      ),
      5.seconds
    )
    val yksilollistamiset: Seq[String] =
      run(database.run(sql"select yksilollistaminen from suoritus".as[String]))
    yksilollistamiset.head should equal("Alueittain")
    val suoritusTilat: Seq[String] = run(database.run(sql"select tila from suoritus".as[String]))
    suoritusTilat.head should equal("VALMIS")
    suoritusTilat.size should equal(1)
  }

  it should "not store katsotaaneronneeksi VALMA-suoritus less than 30 opintopistettä before or after deadline date" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "koskidata_valma_vain_katsotaaneronneeksi.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    KoskiUtil.deadlineDate = LocalDate.now().plusDays(1)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = false)
      ),
      5.seconds
    )

    var opiskelija = run(database.run(sql"select count(*) from opiskelija".as[String]))
    opiskelija.head should equal("0")
    var suoritukset = run(database.run(sql"select count(*) from suoritus".as[String]))
    suoritukset.head should equal("0")

    KoskiUtil.deadlineDate = LocalDate.now().minusDays(1)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        new KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = false)
      ),
      5.seconds
    )

    opiskelija = run(database.run(sql"select count(*) from opiskelija".as[String]))
    opiskelija.head should equal("0")
    suoritukset = run(database.run(sql"select count(*) from suoritus".as[String]))
    suoritukset.head should equal("0")
  }

  it should "save a valmis kotiopetuslainen as valmis peruskoulun suoritus even without ysiluokka" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "valmistunut_kotiopetuslainen.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    KoskiUtil.deadlineDate = LocalDate.now().plusDays(1)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = false)
      ),
      5.seconds
    )

    val opiskelija = run(database.run(sql"select count(*) from opiskelija".as[String]))
    opiskelija.head should equal("1")
    val suoritukset = run(database.run(sql"select count(*) from suoritus".as[String]))
    suoritukset.head should equal("1")
  }

  it should "not save a valmis kotiopetuslainen as valmis peruskoulun suoritus if valmistuminen is after deadline" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "valmistunut_kotiopetuslainen.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    val valmistumisDate =
      new LocalDate(henkilo.opiskeluoikeudet.head.suoritukset.head.vahvistus.get.päivä)
    KoskiUtil.deadlineDate = valmistumisDate.minusDays(1)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = false)
      ),
      5.seconds
    )

    val opiskelija = run(database.run(sql"select count(*) from opiskelija".as[String]))
    opiskelija.head should equal("0")
    val suoritukset = run(database.run(sql"select count(*) from suoritus".as[String]))
    suoritukset.head should equal("0")
  }

  it should "save not save a lasna-tilainen kotiopetuslainen" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "kotiopetus_lasna.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    KoskiUtil.deadlineDate = LocalDate.now().plusDays(1)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = false)
      ),
      5.seconds
    )

    val opiskelija = run(database.run(sql"select count(*) from opiskelija".as[String]))
    opiskelija.head should equal("0")
    val suoritukset = run(database.run(sql"select count(*) from suoritus".as[String]))
    suoritukset.head should equal("0")
  }

  it should "not save eronnut kotiopetuslainen" in {
    val json: String =
      scala.io.Source.fromFile(jsonDir + "kotiopetus_eronnut.json").mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    KoskiUtil.deadlineDate = LocalDate.now().plusDays(1)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = false)
      ),
      5.seconds
    )

    val opiskelija = run(database.run(sql"select count(*) from opiskelija".as[String]))
    opiskelija.head should equal("0")
    val suoritukset = run(database.run(sql"select count(*) from suoritus".as[String]))
    suoritukset.head should equal("0")
  }

  it should "save a valmis kotiopetuslainen with legacy kotiopetus format" +
    " as valmis peruskoulun suoritus even without ysiluokka" in {
      val json: String =
        scala.io.Source.fromFile(jsonDir + "valmistunut_kotiopetuslainen_legacy.json").mkString
      val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
      henkilo should not be null
      henkilo.opiskeluoikeudet.head.tyyppi should not be empty
      KoskiUtil.deadlineDate = LocalDate.now().plusDays(1)

      Await.result(
        koskiDatahandler.processHenkilonTiedotKoskesta(
          henkilo,
          PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
          KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = false)
        ),
        5.seconds
      )

      val opiskelija = run(database.run(sql"select count(*) from opiskelija".as[String]))
      opiskelija.head should equal("1")
      val suoritukset = run(database.run(sql"select count(*) from suoritus".as[String]))
      suoritukset.head should equal("1")
    }

  it should "Save suoritus as keskeytynyt if it is vahvistettu after deadline and now is after deadline" in {
    val json: String =
      scala.io.Source
        .fromFile(jsonDir + "peruskoulu_9_luokka_päättötodistus_vahvistus_4_6_2018_jälkeen.json")
        .mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    val vp: LocalDate =
      parseLocalDate(
        henkilo.opiskeluoikeudet.head.suoritukset
          .find(s => s.luokka.getOrElse("").equals("9C"))
          .get
          .vahvistus
          .get
          .päivä
      )
    KoskiUtil.deadlineDate = vp.minusDays(1)

    Await.result(
      koskiDatahandler.processHenkilonTiedotKoskesta(
        henkilo,
        PersonOidsWithAliases(henkilo.henkilö.oid.toSet),
        KoskiSuoritusHakuParams(saveLukio = true, saveAmmatillinen = false)
      ),
      5.seconds
    )

    val opiskelija = run(database.run(sql"select count(*) from opiskelija".as[String]))
    opiskelija.head should equal("1")
    val suoritukset = run(database.run(sql"select count(*) from suoritus".as[String]))
    suoritukset.head should equal("1")
    val suoritustila = run(database.run(sql"select tila from suoritus".as[String]))
    suoritustila.head should equal("KESKEYTYNYT")
  }

  def getPerusopetusPäättötodistus(arvosanat: Seq[SuoritusArvosanat]): Option[SuoritusArvosanat] = {
    arvosanat.find(_.suoritus.komo.contentEquals(Oids.perusopetusKomoOid))
  }

  def getYsiluokat(arvosanat: Seq[SuoritusArvosanat]): Seq[SuoritusArvosanat] = {
    val luokat =
      arvosanat.filter(a => a.suoritus.komo.contentEquals("luokka") && a.luokka.startsWith("9"))
    luokat
  }

  def getHenkilo(jsonFileName: String): KoskiHenkiloContainer = {
    val json: String = scala.io.Source.fromFile(jsonDir + jsonFileName).mkString
    val henkilo: KoskiHenkiloContainer = parse(json).extract[KoskiHenkiloContainer]
    henkilo should not be null
    henkilo.opiskeluoikeudet.head.tyyppi should not be empty
    henkilo
  }

  def getPerusopetusB2Kielet(arvosanat: Seq[SuoritusArvosanat]): Seq[Arvosana] = {
    val pk: Option[SuoritusArvosanat] = getPerusopetusPäättötodistus(arvosanat)
    pk match {
      case Some(t) => t.arvosanat.filter(_.aine.contentEquals("B2"))
      case None    => Seq.empty
    }
  }

  def peruskouluB2KieletShouldNotBeValinnainen(arvosanat: Seq[SuoritusArvosanat]): Unit = {
    getPerusopetusB2Kielet(arvosanat).foreach(_.valinnainen shouldEqual false)
  }

  def fakeHenkilonSuorituksetSavedAt(
    henkiloOid: String,
    lastModified: Long = System.currentTimeMillis() - (1000 * 60 * 60 * 24)
  ): Unit = {

    val lahdeArvotString: String = "{\"last modified\":\"" + lastModified.toString + "\"}"
    run(
      database.run(
        sql"update suoritus set lahde_arvot = $lahdeArvotString where henkilo_oid = $henkiloOid"
          .as[String]
      )
    )
  }

  class TestSureActor extends Actor {
    import akka.pattern.pipe

    override def receive: Receive = {
      case SuoritusQueryWithPersonAliases(q, personOidsWithAliases) =>
        //(henkilo: String, kuvaus: String, myontaja: String, vuosi: Int, tyyppi: String, index: Int = 0, lahde: String) extends Suoritus (henkilo, false, lahde) {
        val existing: VirallinenSuoritus = VirallinenSuoritus(
          komo = "komo",
          myontaja = "myontaja",
          tila = "KESKEN",
          valmistuminen = new LocalDate(),
          henkilo = "1.2.246.562.24.71123947024",
          yksilollistaminen = yksilollistaminen.Ei,
          suoritusKieli = "FI",
          lahde = "1.2.246.562.10.1234",
          vahv = false
        )
        //("1.2.246.562.24.71123947024", true, "koski") //val henkiloOid: String, val vahvistettu: Boolean, val source: String
        //Future.failed(new RuntimeException("test")) pipeTo sender
        Future.successful(Seq()) pipeTo sender
    }
  }

  private def run[T](f: Future[T]): T = Await.result(f, atMost = timeout.duration)

}
