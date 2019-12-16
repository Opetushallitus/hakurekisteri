package fi.vm.sade.hakurekisteri.integration.ytl

import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

import akka.actor.Status.{Failure, Success}
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.arvosana.{Arvio410, ArvioYo, Arvosana, ArvosanatQuery}
import fi.vm.sade.hakurekisteri.integration.OphUrlProperties
import fi.vm.sade.hakurekisteri.integration.hakemus.{FullHakemus, HakemusAnswers, HakemusHenkilotiedot, HakemusService, HetuPersonOid}
import fi.vm.sade.hakurekisteri.integration.henkilo.{IOppijaNumeroRekisteri, PersonOidsWithAliases}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.suoritus.{SuoritusQuery, VirallinenSuoritus, yksilollistaminen}
import fi.vm.sade.hakurekisteri.test.tools.ClassPathUtil
import fi.vm.sade.hakurekisteri.tools.ItPostgres
import fi.vm.sade.hakurekisteri.{Config, MockConfig}
import fi.vm.sade.scalaproperties.OphProperties
import org.joda.time.format.DateTimeFormat
import org.joda.time.{LocalDate, LocalDateTime}
import org.json4s.Formats
import org.json4s.jackson.JsonMethods
import org.mockito
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest._
import org.scalatest.mockito.MockitoSugar
import org.slf4j.LoggerFactory
import support.{BareRegisters, DbJournals, PersonAliasesProvider}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, TimeoutException}


class YtlIntegrationSpec extends FlatSpec with BeforeAndAfterEach with BeforeAndAfterAll with Matchers with MockitoSugar {
  private val logger = LoggerFactory.getLogger(getClass)

  private implicit val database = Database.forURL(ItPostgres.getEndpointURL)
  private implicit val system = ActorSystem("test-jdbc")
  private implicit val ec: ExecutionContext = system.dispatcher
  private implicit val timeout: Timeout = Timeout(10, TimeUnit.SECONDS)

  private val ophProperties: OphProperties = OphUrlProperties
  private val ytlHttpClient: YtlHttpFetch = mock[YtlHttpFetch]
  private val hakemusService: HakemusService = mock[HakemusService]
  private val failureEmailSenderMock: FailureEmailSender = mock[FailureEmailSender]
  private val config: MockConfig = new MockConfig
  private val journals: DbJournals = new DbJournals(config)

  private val activeHakuOid = "1.2.246.562.29.26435854158"
  private val henkiloOid = "1.2.246.562.24.58341904891"
  private val ssn = "091001A941F"

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

  class NeverEndingActor extends Actor {
    override def receive: Receive = {
      case x =>
        logger.info(s"NeverEndingActor has received message $x and will never reply to sender $sender")
    }
  }
  val neverEndingActor: ActorRef = system.actorOf(Props(new NeverEndingActor), "never-ending")

  class FailingActor extends Actor {
    override def receive: Receive = {
      case x =>
        logger.info(s"FailingActor has received message $x and will send a failure")
        sender() ! akka.actor.Status.Failure(new RuntimeException("Forced to fail"))
    }
  }
  val failingActor: ActorRef = system.actorOf(Props(new FailingActor), "failing")

  private val rekisterit: BareRegisters = new BareRegisters(system, journals, database, personAliasesProvider, config)

  trait UseYtlKokelasPersister {
    def createTestYtlKokelasPersister(suoritusRekisteri: ActorRef = rekisterit.ytlSuoritusRekisteri,
                                      arvosanaRekisteri: ActorRef = rekisterit.ytlArvosanaRekisteri): YtlKokelasPersister = {
      val testYtlKokelasPersister = new YtlKokelasPersister(
        system,
        suoritusRekisteri,
        arvosanaRekisteri,
        hakemusService,
        Timeout(3.seconds),
        2)
      testYtlKokelasPersister
    }
  }

  trait UseYtlIntegration {
    def createTestYtlIntegration(testYtlKokelasPersister: YtlKokelasPersister): YtlIntegration = {
      val ytlIntegration = new YtlIntegration(
        ophProperties, ytlHttpClient, hakemusService,
        oppijaNumeroRekisteri, testYtlKokelasPersister, config)
      ytlIntegration.setAktiivisetKKHaut(Set(activeHakuOid))
      ytlIntegration
    }
  }

  trait HakemusForPerson {
    Mockito.when(hakemusService.hakemuksetForPerson(henkiloOid)).thenReturn(Future.successful(Seq(
      FullHakemus(activeHakuOid,
        Some(henkiloOid),
        activeHakuOid,
        Some(HakemusAnswers(Some(HakemusHenkilotiedot(Henkilotunnus = Some(ssn))))), Some("ACTIVE"), Seq())
    )))

    Await.result(rekisterit.ytlSuoritusRekisteri ? createTestSuoritus(henkiloOid),
      Duration(30, TimeUnit.SECONDS))
  }

  trait ExampleArvosana {
    def createExampleArvosana(suoritusId: UUID) = {
      val testArvosana = Arvosana(suoritusId, arvio = Arvio410("S"), "MA",
        lisatieto = None, valinnainen = true, myonnetty = None, source = "person1", Map(), Some(1))
      Await.result(rekisterit.ytlArvosanaRekisteri ? testArvosana, Duration(30, TimeUnit.SECONDS))
    }
  }

  trait HakemusServiceSingleEntry {
    Mockito.when(hakemusService.hetuAndPersonOidForHaku(activeHakuOid)).thenReturn(Future.successful(Seq(
      HetuPersonOid(ssn, henkiloOid)
    )))
    Mockito.when(ytlHttpClient.fetchOne(mockito.ArgumentMatchers.any(classOf[String])))
      .thenReturn(Some("{}", Student(ssn, "surname", "name", language = "fi", exams = Seq())))
  }

  trait KokelasWithPersonAliases {
    val kokelasWithPersonAliases = KokelasWithPersonAliases(StudentToKokelas.convert(henkiloOid, createTestStudent(ssn)),
      PersonOidsWithAliases(Set(henkiloOid), Map(henkiloOid -> Set(henkiloOid))))
  }

  trait KokelasWithTooManyPersonOids {
    val dummyVirallinenSuoritus =  VirallinenSuoritus("", "", "",
      new LocalDate("2019-12-21"), "", yksilollistaminen.Ei, "",
      None, true, "", None, Map())
    val kokelasWithTooManyPersonOids = KokelasWithPersonAliases(
      Kokelas("", dummyVirallinenSuoritus, List(), List()),
      PersonOidsWithAliases(Set("1.2.3.4.5.6", "1.2.3.4.5.7"), Map()))
  }

  trait ExampleSuoritus {
    val komo = "1.2.246.562.5.2013061010184237348007"
    val suoritus: VirallinenSuoritus with Identified[UUID] = VirallinenSuoritus(
      komo, "1.2.246.562.10.43628088406", "KESKEN",
      new LocalDate("2019-12-21"), "1.2.246.562.24.58341904891", yksilollistaminen.Ei, "FI",
      None, true, "1.2.246.562.10.43628088406", None, Map()).identify(UUID.randomUUID())
  }

  private val tenEntries = Seq(
    HetuPersonOid("030288-9552", "1.2.246.562.24.97187447816"),
    HetuPersonOid("060141-9297", "1.2.246.562.24.26258799406"),
    HetuPersonOid("081007-982P", "1.2.246.562.24.28012286739"),
    HetuPersonOid("091001A941F", "1.2.246.562.24.58341904891"),
    HetuPersonOid("101206-919A", "1.2.246.562.24.72419942839"),
    HetuPersonOid("111028-9213", "1.2.246.562.24.69534493441"),
    HetuPersonOid("121096-901M", "1.2.246.562.24.27918240375"),
    HetuPersonOid("210253-989R", "1.2.246.562.24.48985825650"),
    HetuPersonOid("210955-920N", "1.2.246.562.24.82063315187"),
    HetuPersonOid("281000-967A", "1.2.246.562.24.95499907842")
  )

  trait HakemusServiceTenEntries {
    Mockito.when(hakemusService.hetuAndPersonOidForHaku(activeHakuOid)).thenReturn(Future.successful(tenEntries))
    val jsonStringFromFile = ClassPathUtil.readFileFromClasspath(getClass, "student-results-from-ytl.json")
    implicit val formats: Formats = Student.formatsStudent
    val studentsFromYtlTestData: Seq[Student] = JsonMethods.parse(jsonStringFromFile).extract[Seq[Student]]

    val zipResults: Iterator[Either[Throwable, (ZipInputStream, Iterator[Student])]] = Seq(Right((mock[ZipInputStream],
      studentsFromYtlTestData.iterator))).iterator

    Mockito.when(ytlHttpClient.fetch(mockito.ArgumentMatchers.any(classOf[String]), mockito.ArgumentMatchers.any())).thenReturn(zipResults)
  }

  override protected def beforeEach(): Unit = {
    Mockito.reset(hakemusService, oppijaNumeroRekisteri, failureEmailSenderMock, ytlHttpClient)
    Mockito.when(oppijaNumeroRekisteri.enrichWithAliases(mockito.ArgumentMatchers.any(classOf[Set[String]]))).thenAnswer(new Answer[Future[PersonOidsWithAliases]] {
      override def answer(invocation: InvocationOnMock): Future[PersonOidsWithAliases] = {
        val henkiloOids = invocation.getArgument[Set[String]](0)
        Future.successful(PersonOidsWithAliases(henkiloOids, henkiloOids.map(h => (h, Set(h))).toMap, henkiloOids))
      }
    })
    ItPostgres.reset()
  }

  override protected def afterAll(): Unit = {
    Await.result(system.terminate(), 30.seconds)
    database.close()
  }

  def createTestStudent(ssn: String) = Student(ssn = ssn, lastname = "Test", firstnames = "Test",
    graduationPeriod = Some(Kevat(2003)),
    graduationDate = Some(DateTimeFormat.forPattern("yyyy-MM-dd").parseLocalDate("2003-05-31")),
    certificateSchoolOphOid = Some("1.2.246.562.10.63670951381"),
    certificateSchoolYtlNumber = Some("1254"),
    hasCompletedMandatoryExams = Some(true),
    language = "FI",
    exams = Seq.empty)
  def createTestSuoritus(henkiloOid: String) = VirallinenSuoritus(
    komo = "1.2.246.562.5.2013061010184237348007",
    myontaja = "1.2.246.562.10.43628088406",
    henkilo = henkiloOid,
    yksilollistaminen = yksilollistaminen.Ei,
    suoritusKieli = "FI",
    lahde = "1.2.246.562.10.43628088406",
    tila = "KESKEN",
    lahdeArvot = Map("hasCompletedMandatoryExams" -> "false"),
    valmistuminen = DateTimeFormat.forPattern("yyyy-MM-dd").parseDateTime("2018-12-21").toLocalDate
  )

  behavior of "YoSuoritusUpdateActor's ask pattern"

  it should "succeed when correct data" in new KokelasWithPersonAliases {
    val yoSuoritusUpdateActor: ActorRef = system.actorOf(YoSuoritusUpdateActor.props(
      kokelasWithPersonAliases.kokelas.yo,
      kokelasWithPersonAliases.personOidsWithAliases,
      rekisterit.ytlSuoritusRekisteri))

    val future = akka.pattern.ask(yoSuoritusUpdateActor, YoSuoritusUpdateActor.Update)
    future map { result =>
      result shouldBe a[VirallinenSuoritus with Identified[_]]
    }
  }

  it should "fail when incorrect data" in new KokelasWithTooManyPersonOids {
    val yoSuoritusUpdateActor: ActorRef = system.actorOf(YoSuoritusUpdateActor.props(
      kokelasWithTooManyPersonOids.kokelas.yo,
      kokelasWithTooManyPersonOids.personOidsWithAliases,
      rekisterit.ytlSuoritusRekisteri))
    var failedAsExpected = false

    val future = akka.pattern.ask(yoSuoritusUpdateActor, YoSuoritusUpdateActor.Update).recover {
      case e: IllegalArgumentException =>
        e.getMessage should startWith ("Got 2 person aliases")
        failedAsExpected = true
        Failure(e)
      case e =>
        fail("Unexpected way to fail", e)
    }
    val result = Await.result(future, 5.seconds)

    result shouldBe a[Failure]
    failedAsExpected should be (true)
  }

  behavior of "ArvosanaUpdateActor's ask pattern"

  it should "succeed when everything is ok with arvosanaRekisteri" in new KokelasWithPersonAliases with ExampleSuoritus with ExampleArvosana {
    createExampleArvosana(suoritus.id)
    val kokelas = kokelasWithPersonAliases.kokelas
    val arvosanaUpdateActor: ActorRef = system.actorOf(ArvosanaUpdateActor.props(
      kokelas.yoTodistus ++ kokelas.osakokeet,
      rekisterit.ytlArvosanaRekisteri,
      config.ytlSyncTimeout.duration))

    val future = akka.pattern.ask(arvosanaUpdateActor, ArvosanaUpdateActor.Update(suoritus))
    try {
      Await.ready(future, 5.seconds)
      succeed
    } catch {
      case e: Throwable => fail(e)
    }
  }

  it should "fail when arvosanaRekisteri actor fails" in new ExampleSuoritus {
    val arvosanaUpdateFailingActor: ActorRef = system.actorOf(ArvosanaUpdateActor.props(
      Seq(),
      arvosanaRekisteri = failingActor,
      config.ytlSyncTimeout.duration))
    var failedAsExpected = false

    val future = akka.pattern.ask(arvosanaUpdateFailingActor, ArvosanaUpdateActor.Update(suoritus)).recover {
      case e: IllegalArgumentException =>
        e.getMessage should include ("Forced to fail")
        failedAsExpected = true
        Failure(e)
      case e =>
        fail("Unexpected way to fail", e)
    }
    val result = Await.result(future, 5.seconds)

    result shouldBe a[Failure]
    failedAsExpected should be (true)
  }

  it should "fail when arvosanaRekisteri actor gets stuck" in new ExampleSuoritus {
    val arvosanaUpdateFailingActor: ActorRef = system.actorOf(ArvosanaUpdateActor.props(
      Seq(),
      arvosanaRekisteri = neverEndingActor,
      config.ytlSyncTimeout.duration))
    var failedAsExpected = false

    val future = akka.pattern.ask(arvosanaUpdateFailingActor, ArvosanaUpdateActor.Update(suoritus))
    try {
      val result = Await.result(future, 5.seconds)
      fail("should not be here")
    } catch {
      case x: TimeoutException =>
        failedAsExpected = true
    }

    failedAsExpected should be (true)
  }


  behavior of "YtlKokelasPersister persistSingle"

  it should "update existing YTL suoritukset" in new HakemusServiceSingleEntry with KokelasWithPersonAliases with UseYtlKokelasPersister {
    val realKokelasPersister = createTestYtlKokelasPersister()

    val future = realKokelasPersister.persistSingle(kokelasWithPersonAliases)
    try {
      Await.ready(future, 5.seconds)

      Thread.sleep(500)
      val suoritukset: Seq[VirallinenSuoritus with Identified[UUID]] = findAllSuoritusFromDatabase.filter(_.henkilo == henkiloOid)

      suoritukset should have size 1
      suoritukset.head.lahdeArvot should equal(Map("hasCompletedMandatoryExams" -> "true"))
    } catch {
      case e: Throwable => fail(e)
    }
  }

  it should "fail if input data is invalid" in new HakemusServiceSingleEntry with KokelasWithTooManyPersonOids with UseYtlKokelasPersister {
    var failedAsExpected = false
    val realKokelasPersister = createTestYtlKokelasPersister()

    val future = realKokelasPersister.persistSingle(kokelasWithTooManyPersonOids).recover {
      case e: IllegalArgumentException =>
        e.getMessage should startWith ("Got 2 person aliases")
        failedAsExpected = true
      case e =>
        fail("Unexpected way to fail", e)
    }
    val result = Await.result(future, 5.seconds)

    failedAsExpected should be (true)
  }

  it should "fail if arvosana update fails" in new HakemusServiceSingleEntry with KokelasWithPersonAliases with UseYtlKokelasPersister {
    var failedAsExpected = false
    val kokelasPersisterWithFailingArvosanaUpdater = createTestYtlKokelasPersister(arvosanaRekisteri = failingActor)

    val future = kokelasPersisterWithFailingArvosanaUpdater.persistSingle(kokelasWithPersonAliases).recover {
      case e: Exception =>
        e.getMessage should include ("Run out of retries")
        e.getCause.getMessage should include ("Forced to fail")
        failedAsExpected = true
      case e =>
        fail("Unexpected way to fail", e)
    }
    val result = Await.result(future, 5.seconds)

    failedAsExpected should be (true)
  }

  it should "fail if arvosana update gets stuck" in new HakemusServiceSingleEntry with KokelasWithPersonAliases with UseYtlKokelasPersister {
    var failedAsExpected = false
    val kokelasPersisterWithStuckArvosanaUpdater = createTestYtlKokelasPersister(arvosanaRekisteri = neverEndingActor)

    val future = kokelasPersisterWithStuckArvosanaUpdater.persistSingle(kokelasWithPersonAliases).recover {
      case e: Exception =>
        e.getMessage should include ("Run out of retries")
        e.getCause.getMessage should include ("Ask timed out")
        failedAsExpected = true
      case e =>
        fail("Unexpected way to fail", e)
    }
    val result = Await.result(future, 30.seconds)

    failedAsExpected should be (true)
  }


  behavior of "YtlIntegration sync"

  it should "update existing YTL suoritukset" in new UseYtlKokelasPersister with UseYtlIntegration with HakemusForPerson with HakemusServiceSingleEntry with ExampleSuoritus {
    val realKokelasPersister = createTestYtlKokelasPersister()
    val ytlIntegration = createTestYtlIntegration(realKokelasPersister)

    val future = ytlIntegration.sync(henkiloOid)
    val result = Await.result(future, 5.seconds)

    result should have size 1
    result(0) should matchPattern { case scala.util.Success(Kokelas(_, _, _, _)) => }

    val expectedResult =
      Kokelas("1.2.246.562.24.58341904891", suoritus, List(), List())
    val testKokelas: Kokelas = result.head.get
    testKokelas should be (expectedResult)

    val suoritukset: Seq[VirallinenSuoritus with Identified[UUID]] = findAllSuoritusFromDatabase.filter(_.henkilo == henkiloOid)
    suoritukset should have size 1
    suoritukset.head.komo should equal(komo)
  }

  it should "return failure if does not succeed in updating ytl (ytl persister gets stuck)" in new UseYtlKokelasPersister
    with UseYtlIntegration with HakemusForPerson with HakemusServiceSingleEntry with Inside {

    val kokelasPersisterWhichGetsStuck = createTestYtlKokelasPersister(arvosanaRekisteri = neverEndingActor)
    val ytlIntegration = createTestYtlIntegration(kokelasPersisterWhichGetsStuck)

    val future = ytlIntegration.sync(henkiloOid)
    val result = Await.result(future, 15.seconds)

    result should have size 1
    inside(result(0)) { case scala.util.Failure(e: RuntimeException) =>
      e.getMessage should include ("Persist kokelas 1.2.246.562.24.58341904891 failed")
      e.getCause.getMessage should include ("ArvosanaUpdate: Run out of retries")
      e.getCause.getCause.getMessage should include ("Ask timed out")
    }
  }

  it should "return failure if ytl persister fails" in new UseYtlKokelasPersister with UseYtlIntegration
    with HakemusForPerson with HakemusServiceSingleEntry with Inside {
    val kokelasPersisterWhichFails = createTestYtlKokelasPersister(arvosanaRekisteri = failingActor)
    val ytlIntegration = createTestYtlIntegration(kokelasPersisterWhichFails)

    val future = ytlIntegration.sync(henkiloOid)
    val result = Await.result(future, 10.seconds)

    result should have size 1
    inside(result(0)) { case scala.util.Failure(e: RuntimeException) =>
      e.getMessage should include ("Persist kokelas 1.2.246.562.24.58341904891 failed")
      e.getCause.getMessage should include ("ArvosanaUpdate: Run out of retries")
      e.getCause.getCause.getMessage should include ("Forced to fail")
    }
  }

  behavior of "YtlIntegration syncAll"

  it should "successfully insert new suoritus and arvosana records from YTL data, no failure email is sent" in
                new UseYtlKokelasPersister with UseYtlIntegration with HakemusServiceTenEntries {
    findAllSuoritusFromDatabase should be(Nil)
    findAllArvosanasFromDatabase should be(Nil)
    val realKokelasPersister = createTestYtlKokelasPersister()
    val ytlIntegration = createTestYtlIntegration(realKokelasPersister)

    ytlIntegration.syncAll(failureEmailSender = failureEmailSenderMock)

    Thread.sleep(100)

    val mustBeReadyUntil = new LocalDateTime().plusMinutes(1)
    while (new LocalDateTime().isBefore(mustBeReadyUntil) &&
      (findAllSuoritusFromDatabase.size < 10 || findAllArvosanasFromDatabase.size < 89)) {
      Thread.sleep(50)
    }
    val allSuoritusFromDatabase = findAllSuoritusFromDatabase.sortBy(_.henkilo)
    val allArvosanasFromDatabase = findAllArvosanasFromDatabase.sortBy(a => (a.aine, a.lisatieto, a.arvio.toString))
    allSuoritusFromDatabase should have size 10
    allArvosanasFromDatabase should have size 89

    val virallinenSuoritusToExpect = VirallinenSuoritus(
      komo = "1.2.246.562.5.2013061010184237348007",
      myontaja = "1.2.246.562.10.43628088406",
      tila = "VALMIS",
      valmistuminen = new LocalDate(2012, 6, 1),
      henkilo = "1.2.246.562.24.26258799406",
      yksilollistaminen = fi.vm.sade.hakurekisteri.suoritus.yksilollistaminen.Ei,
      suoritusKieli = "FI",
      opiskeluoikeus = None,
      vahv = true,
      lahde = "1.2.246.562.10.43628088406",
      lahdeArvot = Map("hasCompletedMandatoryExams" -> "true"))
    allSuoritusFromDatabase.head should be(virallinenSuoritusToExpect)

    val arvosanaToExpect = Arvosana(
      suoritus = allArvosanasFromDatabase.head.suoritus,
      arvio = ArvioYo("C", Some(216)),
      aine = "A",
      lisatieto = Some("EN"),
      valinnainen = false,
      myonnetty = Some(new LocalDate(2012, 6, 1)),
      source = "1.2.246.562.10.43628088406",
      lahdeArvot = Map("koetunnus" -> "EA", "aineyhdistelmarooli" -> "31"),
      jarjestys = None)
    allArvosanasFromDatabase.head should be(arvosanaToExpect)

    val expectedNumberOfOnrCalls = 1
    Mockito.verify(oppijaNumeroRekisteri, Mockito.times(expectedNumberOfOnrCalls)).enrichWithAliases(mockito.ArgumentMatchers.any(classOf[Set[String]]))
    Mockito.verifyNoMoreInteractions(oppijaNumeroRekisteri)

    Mockito.verify(failureEmailSenderMock, Mockito.never()).sendFailureEmail(mockito.ArgumentMatchers.any(classOf[String]))
  }

  it should "fail if not all suoritus and arvosana records were successfully inserted to ytl" in
        new UseYtlKokelasPersister with UseYtlIntegration with HakemusServiceTenEntries {
    val kokelasPersisterWhichFails = createTestYtlKokelasPersister(arvosanaRekisteri = failingActor)
    val ytlIntegration = createTestYtlIntegration(kokelasPersisterWhichFails)

    ytlIntegration.syncAll(failureEmailSender = failureEmailSenderMock)

    Thread.sleep(1000)

    Mockito.verify(failureEmailSenderMock, Mockito.times(1)).sendFailureEmail(mockito.ArgumentMatchers.any(classOf[String]))
  }

  it should "fail if ytl persister gets stuck" in new UseYtlKokelasPersister with UseYtlIntegration with HakemusServiceTenEntries {
    val kokelasPersisterWhichGetsStuck = createTestYtlKokelasPersister(arvosanaRekisteri = neverEndingActor)
    val ytlIntegration = createTestYtlIntegration(kokelasPersisterWhichGetsStuck)

    ytlIntegration.syncAll(failureEmailSender = failureEmailSenderMock)

    Thread.sleep(11000)

    Mockito.verify(failureEmailSenderMock, Mockito.times(1)).sendFailureEmail(mockito.ArgumentMatchers.any(classOf[String]))
  }

  it should "fail if ytl fetch returns throwables" in new UseYtlKokelasPersister with UseYtlIntegration {
    Mockito.when(hakemusService.hetuAndPersonOidForHaku(activeHakuOid)).thenReturn(Future.successful(tenEntries))

    private val ytlHttpClientThatReturnsThrowables: YtlHttpFetch = mock[YtlHttpFetch]
    private val lefts = Seq(Left(new RuntimeException("mocked failure")))
    Mockito.when(ytlHttpClientThatReturnsThrowables.fetch(mockito.ArgumentMatchers.any(classOf[String]), mockito.ArgumentMatchers.any(classOf[Seq[String]])))
      .thenReturn(lefts.toIterator)

    val realKokelasPersister = createTestYtlKokelasPersister()
    val ytlIntegration = createTestYtlIntegration(realKokelasPersister)

    ytlIntegration.syncAll(failureEmailSender = failureEmailSenderMock)

    Thread.sleep(1000)

    Mockito.verify(failureEmailSenderMock, Mockito.times(1)).sendFailureEmail(mockito.ArgumentMatchers.any(classOf[String]))
  }

  it should "fail if ytl fetch throws" in new UseYtlKokelasPersister with UseYtlIntegration {
    Mockito.when(hakemusService.hetuAndPersonOidForHaku(activeHakuOid)).thenReturn(Future.successful(tenEntries))

    private val ytlHttpClientThatThrows: YtlHttpFetch = mock[YtlHttpFetch]
    Mockito.when(ytlHttpClientThatThrows.fetch(mockito.ArgumentMatchers.any(classOf[String]), mockito.ArgumentMatchers.any(classOf[Seq[String]])))
      .thenThrow(new RuntimeException("mocked failure"))

    val realKokelasPersister = createTestYtlKokelasPersister()
    val ytlIntegration = createTestYtlIntegration(realKokelasPersister)

    ytlIntegration.syncAll(failureEmailSender = failureEmailSenderMock)

    Thread.sleep(1000)

    Mockito.verify(failureEmailSenderMock, Mockito.times(1)).sendFailureEmail(mockito.ArgumentMatchers.any(classOf[String]))
  }

  it should "fail if tried to start before the previous syncAll was not finished" in
    new UseYtlKokelasPersister with UseYtlIntegration with HakemusServiceTenEntries {
      val kokelasPersisterWhichGetsStuck = createTestYtlKokelasPersister(arvosanaRekisteri = neverEndingActor)
      val ytlIntegration = createTestYtlIntegration(kokelasPersisterWhichGetsStuck)
      ytlIntegration.syncAll(failureEmailSender = failureEmailSenderMock)
      Thread.sleep(500)

      val thrown = the [RuntimeException] thrownBy {
        ytlIntegration.syncAll(failureEmailSender = failureEmailSenderMock)
      }
      thrown.getMessage should include("syncAll is already running!")
  }

  it should "succeed to start again when previous syncAll has finished" in
    new UseYtlKokelasPersister with UseYtlIntegration with HakemusServiceTenEntries {
      val kokelasPersisterWhichFails = createTestYtlKokelasPersister(arvosanaRekisteri = failingActor)
      val ytlIntegration = createTestYtlIntegration(kokelasPersisterWhichFails)
      ytlIntegration.syncAll(failureEmailSender = failureEmailSenderMock)
      Thread.sleep(500)

      noException should be thrownBy {
        ytlIntegration.syncAll(failureEmailSender = failureEmailSenderMock)
      }
  }

  private def findAllSuoritusFromDatabase: Seq[VirallinenSuoritus with Identified[UUID]] = {
    findFromDatabase(rekisterit.suoritusRekisteri, SuoritusQuery())
  }

  private def findAllArvosanasFromDatabase: Seq[Arvosana] = {
    val allSuoritusFromDatabase: Seq[VirallinenSuoritus with Identified[UUID]] = findAllSuoritusFromDatabase
    findFromDatabase(rekisterit.arvosanaRekisteri, ArvosanatQuery(allSuoritusFromDatabase.map(_.id).toSet))
  }

  private def findFromDatabase[T](rekisteri: ActorRef, query: AnyRef): T = {
    Await.result(rekisteri ? query, 10.seconds).asInstanceOf[T]
  }
}
