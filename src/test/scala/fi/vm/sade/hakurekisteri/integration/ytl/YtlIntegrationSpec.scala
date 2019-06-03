package fi.vm.sade.hakurekisteri.integration.ytl

import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

import akka.actor.Status.{Failure, Success}
import akka.actor.{Actor, ActorRef, ActorSystem, PoisonPill, Props}
import akka.pattern.ask
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.arvosana.{ArvioYo, Arvosana, ArvosanatQuery}
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
import scala.concurrent.{Await, ExecutionContext, Future}


class YtlIntegrationSpec extends FlatSpec with BeforeAndAfterEach with BeforeAndAfterAll with Matchers with MockitoSugar {
  import YtlActor._

  private val logger = LoggerFactory.getLogger(getClass)

  private implicit val database = Database.forURL(ItPostgres.getEndpointURL)
  private implicit val system = ActorSystem("test-jdbc")
  private implicit val ec: ExecutionContext = system.dispatcher
  private implicit val timeout: Timeout = Timeout(30, TimeUnit.SECONDS)

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

  private val rekisterit: BareRegisters = new BareRegisters(system, journals, database, personAliasesProvider, config)

  val ytlActor: ActorRef = system.actorOf(Props(new YtlActor(
    rekisterit.ytlSuoritusRekisteri,
    rekisterit.ytlArvosanaRekisteri,
    hakemusService,
    config
  )), "ytl")

  class NeverEndingYtlActor extends Actor {
    override def receive: Receive = {
      case k: YtlActor.KokelasWithPersonAliases =>
        logger.info(s"NeverEndingYtlActor has received message about kokelas and will never reply to sender ${k.kokelas}")
    }
  }
  val ytlActorNeverEnding: ActorRef = system.actorOf(Props(new NeverEndingYtlActor), "ytl-never-ending")

  class FailingYtlActor extends Actor {
    override def receive: Receive = {
      case k: YtlActor.KokelasWithPersonAliases =>
        logger.info(s"FailingYtlActor has received message about kokelas and will send a failure")
        sender() ! akka.actor.Status.Failure(new RuntimeException("Forced to fail"))
    }
  }
  val ytlActorFailing: ActorRef = system.actorOf(Props(new FailingYtlActor), "ytl-failing")

  trait UseRealYtlActor {
    val ytlIntegration = new YtlIntegration(ophProperties, ytlHttpClient, hakemusService, oppijaNumeroRekisteri, ytlActor, config)
    ytlIntegration.setAktiivisetKKHaut(Set(activeHakuOid))
  }

  trait UseNeverEndingYtlActor {
    val ytlIntegration = new YtlIntegration(ophProperties, ytlHttpClient, hakemusService, oppijaNumeroRekisteri, ytlActorNeverEnding, config)
    ytlIntegration.setAktiivisetKKHaut((Set(activeHakuOid)))
  }

  trait UseFailingYtlActor {
    val ytlIntegration = new YtlIntegration(ophProperties, ytlHttpClient, hakemusService, oppijaNumeroRekisteri, ytlActorFailing, config)
    ytlIntegration.setAktiivisetKKHaut((Set(activeHakuOid)))
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

  trait SingleEntry {
    Mockito.when(hakemusService.hetuAndPersonOidForHaku(activeHakuOid)).thenReturn(Future.successful(Seq(
      HetuPersonOid(ssn, henkiloOid)
    )))
    Mockito.when(ytlHttpClient.fetchOne(mockito.ArgumentMatchers.any(classOf[String])))
      .thenReturn(Some("{}", Student(ssn, "surname", "name", language = "fi", exams = Seq())))
  }

  trait TenEntries {
    Mockito.when(hakemusService.hetuAndPersonOidForHaku(activeHakuOid)).thenReturn(Future.successful(Seq(
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
    )))
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
    Await.result(system.terminate(), 15.seconds)
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

  behavior of "YtlActor's ask pattern"

  it should "update existing YTL suoritukset" in new UseRealYtlActor with SingleEntry {
    val future = akka.pattern.ask(ytlActor, KokelasWithPersonAliases(StudentToKokelas.convert(henkiloOid, createTestStudent(ssn)),
      PersonOidsWithAliases(Set(henkiloOid), Map(henkiloOid -> Set(henkiloOid)))))
    val result = Await.result(future, 5.seconds)

    result should be (Success)

    val suoritukset: Seq[VirallinenSuoritus with Identified[UUID]] = findAllSuoritusFromDatabase.filter(_.henkilo == henkiloOid)

    suoritukset should have size 1
    suoritukset.head.lahdeArvot should equal(Map("hasCompletedMandatoryExams" -> "true"))
  }

  it should "fail if input data is invalid" in new UseRealYtlActor with SingleEntry {
    val invalidOid = "1.2.246.562.24.123456"
    val dummyVirallinenSuoritus =  VirallinenSuoritus("", "", "",
                                                      new LocalDate("2019-12-21"), "", yksilollistaminen.Ei, "",
                                                      None, true, "", None, Map())
    val kokelasWithTooManyPersonOids = KokelasWithPersonAliases(
      Kokelas("", dummyVirallinenSuoritus, List(), List()),
      PersonOidsWithAliases(Set("1.2.3.4.5.6", "1.2.3.4.5.7"), Map()))
    var failedAsExpected = false

    val future = akka.pattern.ask(ytlActor, kokelasWithTooManyPersonOids).recover {
      case e: IllegalArgumentException =>
        e.getMessage should startWith ("Got 2 person aliases")
        failedAsExpected = true
        Failure(e)
      case e =>
        fail("Unexpected way to fail", e)
    }
    val result = Await.result(future, 5.seconds)

    result shouldBe a [Failure]
    failedAsExpected should be (true)
  }

  behavior of "YtlIntegration sync"
  it should "update existing YTL suoritukset" in new UseRealYtlActor with HakemusForPerson with SingleEntry {
    val komo = "1.2.246.562.5.2013061010184237348007"

    val future = ytlIntegration.sync(henkiloOid)
    val result = Await.result(future, 5.seconds)

    val expectedResult =
      Kokelas("1.2.246.562.24.58341904891",
        VirallinenSuoritus(
          komo, "1.2.246.562.10.43628088406", "KESKEN",
          new LocalDate("2019-12-21"), "1.2.246.562.24.58341904891", yksilollistaminen.Ei, "FI",
          None, true, "1.2.246.562.10.43628088406", None, Map()), List(), List())
    val testKokelas: Kokelas = result.head.get
    testKokelas should be (expectedResult)

    val suoritukset: Seq[VirallinenSuoritus with Identified[UUID]] = findAllSuoritusFromDatabase.filter(_.henkilo == henkiloOid)
    suoritukset should have size 1
    suoritukset.head.komo should equal(komo)
  }

  it should "return failure if does not succeed in updating suoritukset (actor gets stuck)" in new UseNeverEndingYtlActor with HakemusForPerson with SingleEntry {
    val future = ytlIntegration.sync(henkiloOid)
    val result = Await.result(future, 10.seconds)

    result.head shouldBe a [scala.util.Failure[_]]
    val ex: Throwable = result.head.asInstanceOf[scala.util.Failure[RuntimeException]].exception
    ex.getMessage should be (s"Persist kokelas ${henkiloOid} failed")
  }

  it should "return failure if actor fails" in new UseFailingYtlActor with HakemusForPerson with SingleEntry {
    val future = ytlIntegration.sync(henkiloOid)
    val result = Await.result(future, 5.seconds)

    result.head shouldBe a [scala.util.Failure[_]]
    val ex: Throwable = result.head.asInstanceOf[scala.util.Failure[RuntimeException]].exception
    ex.getMessage should be (s"Persist kokelas ${henkiloOid} failed")
  }

  behavior of "YtlIntegration syncAll"
  it should "successfully insert new suoritus and arvosana records from YTL data, no failure email is sent" in
              new UseRealYtlActor with TenEntries {
    findAllSuoritusFromDatabase should be(Nil)
    findAllArvosanasFromDatabase should be(Nil)

    ytlIntegration.syncAll(failureEmailSender = failureEmailSenderMock)

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

  it should "fail if not all suoritus and arvosana records were successfully inserted to ytl" in new UseFailingYtlActor with TenEntries {

    ytlIntegration.syncAll(failureEmailSender = failureEmailSenderMock)
    
    val mustBeReadyUntil = new LocalDateTime().plusSeconds(10)
    while (new LocalDateTime().isBefore(mustBeReadyUntil) &&
      (findAllSuoritusFromDatabase.size < 10 || findAllArvosanasFromDatabase.size < 89)) {
      Thread.sleep(50)
    }

    Mockito.verify(failureEmailSenderMock, Mockito.times(1)).sendFailureEmail(mockito.ArgumentMatchers.any(classOf[String]))
  }

  it should "fail if ytlActor gets stuck" in new UseNeverEndingYtlActor with TenEntries {

    ytlIntegration.syncAll(failureEmailSender = failureEmailSenderMock)

    val mustBeReadyUntil = new LocalDateTime().plusSeconds(10)
    while (new LocalDateTime().isBefore(mustBeReadyUntil) &&
      (findAllSuoritusFromDatabase.size < 10 || findAllArvosanasFromDatabase.size < 89)) {
      Thread.sleep(50)
    }

    Mockito.verify(failureEmailSenderMock, Mockito.times(1)).sendFailureEmail(mockito.ArgumentMatchers.any(classOf[String]))
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
