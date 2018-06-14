package fi.vm.sade.hakurekisteri.integration.ytl

import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import fi.vm.sade.hakurekisteri.arvosana.{ArvioYo, Arvosana, ArvosanatQuery}
import fi.vm.sade.hakurekisteri.integration.OphUrlProperties
import fi.vm.sade.hakurekisteri.integration.hakemus.{HakemusService, HetuPersonOid}
import fi.vm.sade.hakurekisteri.integration.henkilo.{IOppijaNumeroRekisteri, PersonOidsWithAliases}
import fi.vm.sade.hakurekisteri.rest.support.HakurekisteriDriver.api._
import fi.vm.sade.hakurekisteri.storage.Identified
import fi.vm.sade.hakurekisteri.suoritus.{SuoritusQuery, VirallinenSuoritus}
import fi.vm.sade.hakurekisteri.test.tools.ClassPathUtil
import fi.vm.sade.hakurekisteri.tools.ItPostgres
import fi.vm.sade.hakurekisteri.{Config, MockConfig}
import fi.vm.sade.scalaproperties.OphProperties
import org.joda.time.{LocalDate, LocalDateTime}
import org.json4s.Formats
import org.json4s.jackson.JsonMethods
import org.mockito
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import org.scalatest._
import org.scalatest.mock.MockitoSugar
import support.{BareRegisters, DbJournals, PersonAliasesProvider}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}


class YtlIntegrationSpec extends FlatSpec with BeforeAndAfterEach with BeforeAndAfterAll with Matchers with MockitoSugar with ShouldMatchers {
  private implicit val database = Database.forURL(ItPostgres.getEndpointURL)
  private implicit val system = ActorSystem("test-jdbc")
  private implicit val ec: ExecutionContext = system.dispatcher
  private implicit val timeout: Timeout = Timeout(30, TimeUnit.SECONDS)

  private val ophProperties: OphProperties = OphUrlProperties
  private val ytlHttpClient: YtlHttpFetch = mock[YtlHttpFetch]
  private val hakemusService: HakemusService = mock[HakemusService]
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

  private val ytlActor: ActorRef = system.actorOf(Props(new YtlActor(
      rekisterit.ytlSuoritusRekisteri,
      rekisterit.ytlArvosanaRekisteri,
      hakemusService,
      config.integrations.ytlConfig
    )), "ytl")

  private val ytlIntegration = new YtlIntegration(ophProperties, ytlHttpClient, hakemusService, ytlActor, mock[Config])
  private val activeHakuOid = "1.2.246.562.29.26435854158"

  override protected def beforeEach(): Unit = {
    Mockito.when(oppijaNumeroRekisteri.enrichWithAliases(mockito.Matchers.any(classOf[Set[String]]))).thenAnswer(new Answer[Future[PersonOidsWithAliases]] {
      override def answer(invocation: InvocationOnMock): Future[PersonOidsWithAliases] = {
        val henkiloOids = invocation.getArgumentAt(0, classOf[Set[String]])
        Future.successful(PersonOidsWithAliases(henkiloOids, henkiloOids.map(h => (h, Set(h))).toMap, henkiloOids))
      }
    })
    ItPostgres.reset()
    ytlIntegration.setAktiivisetKKHaut(Set(activeHakuOid))
  }

  override protected def afterAll(): Unit = {
    Await.result(system.terminate(), 15.seconds)
    database.close()
  }

  behavior of "YtlIntegration"

  it should "insert new suoritus and arvosana records from YTL data" in {
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

    Mockito.when(ytlHttpClient.fetch(mockito.Matchers.any(classOf[String]), mockito.Matchers.any())).thenReturn(zipResults)

    findAllSuoritusFromDatabase should be(Nil)
    findAllArvosanasFromDatabase should be(Nil)

    ytlIntegration.syncAll()

    val mustBeReadyUntil = new LocalDateTime().plusSeconds(10)
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

    val expectedNumberOfOnrCalls = allSuoritusFromDatabase.size * 2 // NB: This should decrease with BUG-1780
    Mockito.verify(oppijaNumeroRekisteri, Mockito.times(expectedNumberOfOnrCalls)).enrichWithAliases(mockito.Matchers.any(classOf[Set[String]]))
    Mockito.verifyNoMoreInteractions(oppijaNumeroRekisteri)
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
